package scenarios

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import core.ScreenshotCollector
import core.TestError
import core.ErrorType
import core.TestResult
import services.ServiceDefinition
import java.time.Duration
import java.time.Instant

class OIDCLoginScenario : TestScenario {
    override fun execute(
        service: ServiceDefinition,
        context: BrowserContext,
        screenshots: ScreenshotCollector
    ): TestResult {
        val start = Instant.now()
        return try {
            val page = context.newPage()
            page.navigate(service.url)

            // Wait for page to load and look for OIDC/SSO button
            page.waitForTimeout(3000.0)  // Increased wait time for JS-heavy pages

            // Check if already redirected to auth (e.g., JupyterHub auto-redirects)
            if (page.url().contains("auth.")) {
                println("[${service.name}] ✓ Auto-redirected to Authelia, no button click needed")
                // Skip button search, go straight to auth flow
            } else {

            // Build selector list - prioritize service-specific selector if provided
            val oidcButtonSelectors = buildList {
                service.oidcButtonSelector?.let { add(it) }
                addAll(listOf(
                    // Vaultwarden
                    "button.vw-sso-login",
                    "button[class*='sso']",
                    // Planka
                    "button[type='submit']:has-text('Sign in with OIDC')",
                    "a:has-text('Sign in with OIDC')",
                    // JupyterHub
                    "a.service-login",
                    "a[href*='/hub/oauth_login']",
                    // Mastodon
                    "a.button[href*='/auth/auth/']",
                    "a[href*='/oauth/']",
                    // Generic patterns
                    "button:has-text('Use single sign-on')",
                    "button:has-text('single sign-on')",
                    "button:text-is('Log in with SSO')",
                    "button:has-text('SSO')",
                    "a:has-text('Sign in with')",
                    "a:has-text('OIDC')",
                    "a[href*='/auth/']",
                    "[href*='oauth']",
                    "[href*='oidc']",
                    "button:has-text('OAuth')"
                ))
            }

            println("[${service.name}] Searching for OIDC button with ${oidcButtonSelectors.size} selectors")

            var clicked = false
            for (selector in oidcButtonSelectors) {
                try {
                    val element = page.locator(selector).first()
                    val count = page.locator(selector).count()
                    if (count > 0) {
                        // Try visible click first
                        if (element.isVisible()) {
                            println("[${service.name}] Found visible element with selector: $selector")
                            element.click(com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000.0))
                            clicked = true
                            println("[${service.name}] ✓ Clicked OIDC button: $selector")
                            break
                        } else {
                            // For non-visible elements (Angular/React), try multiple strategies
                            println("[${service.name}] Found element (not visible) with selector: $selector, trying alternative clicks")

                            // Strategy 1: Wait for it to be attached to DOM
                            try {
                                page.waitForSelector(selector, Page.WaitForSelectorOptions()
                                    .setTimeout(2000.0)
                                    .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
                            } catch (e: Exception) {
                                // Continue anyway
                            }

                            // Strategy 2: Try MouseEvent simulation for Angular apps
                            try {
                                val mouseEventResult = page.evaluate("""
                                    const btn = document.querySelector('$selector');
                                    if (btn) {
                                        const event = new MouseEvent('click', {
                                            bubbles: true,
                                            cancelable: true,
                                            view: window
                                        });
                                        btn.dispatchEvent(event);
                                        return true;
                                    }
                                    return false;
                                """)
                                if (mouseEventResult == true) {
                                    page.waitForTimeout(1000.0)
                                    // Check if we got redirected
                                    if (page.url().contains("auth.") || !page.url().contains(service.url.substringAfter("://").substringBefore("/"))) {
                                        clicked = true
                                        println("[${service.name}] ✓ MouseEvent click succeeded (detected redirect)")
                                        break
                                    }
                                }
                            } catch (e2: Throwable) {
                                // Try next strategy
                            }

                            // Strategy 3: Force click as last resort
                            try {
                                element.click(com.microsoft.playwright.Locator.ClickOptions().setForce(true).setTimeout(2000.0))
                                clicked = true
                                println("[${service.name}] ✓ Force-clicked OIDC button: $selector")
                                break
                            } catch (e3: Throwable) {
                                println("[${service.name}] All click strategies failed for: $selector")
                                // Continue to next selector
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // Silent fail, try next selector
                }
            }

            if (!clicked) {
                println("[${service.name}] ✗ No OIDC button found with any selector")

                // Capture debug info BEFORE throwing error
                try {
                    val bodyHTML = page.locator("body").innerHTML()
                    println("[${service.name}] DEBUG: Page HTML snippet (first 500 chars):")
                    println(bodyHTML.take(500))

                    // Save full HTML and screenshot for manual inspection
                    screenshots.captureFinal(page, mapOf("error" to "oidc_button_not_found"))
                } catch (e: Throwable) {
                    println("[${service.name}] Could not capture debug HTML: ${e.message}")
                }

                // No OIDC button found, might already be redirected to auth
                if (!page.url().contains("auth.")) {
                    throw Exception("Could not find OIDC/SSO login button at ${page.url()}")
                }
            }
            } // Close the else block for auto-redirect check

            // Wait a moment for redirect to start
            page.waitForTimeout(1000.0)

            // Wait for redirect to Authelia (or timeout gracefully)
            try {
                page.waitForURL("**/auth.**", Page.WaitForURLOptions().setTimeout(10000.0))
            } catch (t: Throwable) {
                // If not redirected to auth, maybe already logged in or different flow
                if (!page.url().contains(service.url.substringAfter("://").substringBefore("/"))) {
                    // We're on a different domain, continue
                } else {
                    // Still on same domain, OIDC might have failed
                    screenshots.captureFinal(page, mapOf("error" to "no_redirect_to_auth"))
                    throw t
                }
            }

            // Check if we're on login page or consent page
            page.waitForTimeout(2000.0)

            if (page.url().contains("flow=openid_connect")) {
                // We're on the consent page (user already logged in)
                println("[${service.name}] On consent page, clicking accept")
                tryClick(page, listOf("#openid-consent-accept", "button:has-text('Accept')"))
            } else {
                // Fill in Authelia login form
                println("[${service.name}] On login page, filling credentials")
                tryFill(page, listOf("#username", "#username-textfield", "input[name='username']"), service.credentials?.username)
                tryFill(page, listOf("#password", "#password-textfield", "input[name='password']"), service.credentials?.password)
                tryClick(page, listOf("button[type='submit']", "#sign-in-button"))

                // After login, might need to handle consent page
                page.waitForTimeout(2000.0)
                if (page.url().contains("flow=openid_connect")) {
                    println("[${service.name}] On consent page after login, clicking accept")
                    tryClick(page, listOf("#openid-consent-accept", "button:has-text('Accept')"))
                }
            }

            // Wait for redirect back to service (or timeout gracefully)
            try {
                page.waitForTimeout(2000.0)  // Give time for redirect to start
                // Check if we're back on the service domain
                val serviceDomain = service.url.substringAfter("://").substringBefore("/")
                if (!page.url().contains(serviceDomain)) {
                    page.waitForURL("**/$serviceDomain**", Page.WaitForURLOptions().setTimeout(30000.0))
                }
            } catch (t: Throwable) {
                // Continue anyway, might already be there
            }

            // Wait for final state selector
            val finalSelector = service.finalWaitSelector
            if (finalSelector != null) {
                try {
                    page.waitForSelector(finalSelector, Page.WaitForSelectorOptions().setTimeout(30000.0))
                } catch (t: Throwable) {
                    // Continue anyway to capture what we got
                }
            }

            // Give page time to fully render
            page.waitForTimeout(2000.0)

            // Capture final state
            screenshots.captureFinal(page)

            val dur = Duration.between(start, Instant.now())
            TestResult.Success(service.name, dur, screenshots.getAll(), emptyList())
        } catch (t: Throwable) {
            TestResult.Failure(
                service.name,
                TestError(ErrorType.UNKNOWN, t.message ?: "Unknown"),
                screenshots.getAll(),
                emptyList()
            )
        }
    }

    private fun tryFill(page: Page, selectors: List<String>, value: String?) {
        if (value == null) return
        for (s in selectors) {
            try {
                if (page.isVisible(s)) {
                    page.fill(s, value)
                    return
                }
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun tryClick(page: Page, selectors: List<String>) {
        for (s in selectors) {
            try {
                if (page.isVisible(s)) {
                    page.click(s)
                    return
                }
            } catch (_: Throwable) { /* ignore */ }
        }
    }
}
