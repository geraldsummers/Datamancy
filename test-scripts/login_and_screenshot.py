#!/usr/bin/env python3
"""Login to each service and take verified screenshots"""
import os
import sys
import time
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

DOMAIN = os.getenv('DOMAIN', 'project-saturn.com')
ADMIN_USER = os.getenv('ADMIN_USER', 'admin')
ADMIN_PASSWORD = os.getenv('ADMIN_PASS', 'dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV')
SCREENSHOT_DIR = os.getenv('SCREENSHOT_DIR', '/screenshots')

def do_authelia_login(page, service_name):
    """Handle Authelia login if we're on the auth page"""
    try:
        # Check if we're on Authelia login page
        if "auth." in page.url:
            print(f"  → On Authelia login page, logging in...")
            page.fill("input#username-textfield", ADMIN_USER, timeout=5000)
            page.fill("input#password-textfield", ADMIN_PASSWORD, timeout=5000)
            page.click("button#sign-in-button")
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=20000)

            # Handle consent page if present
            if "consent" in page.url.lower():
                print(f"  → On consent page, accepting...")
                accept_btn = page.locator("button:has-text('Accept')").or_(page.locator("button:has-text('ACCEPT')"))
                accept_btn.click()
                print(f"  → Waiting for redirect after consent...")
                time.sleep(8)  # Increased wait for redirect
                page.wait_for_load_state("networkidle", timeout=25000)
                print(f"  → After consent, URL: {page.url}")

            return True
        return False
    except Exception as e:
        print(f"  → Auth flow error: {e}")
        return False

def verify_logged_in(page, service_name):
    """Verify we're actually logged in by checking page content"""
    try:
        # Wait a moment for dynamic content to load
        time.sleep(2)

        # Get page HTML
        html = page.content().lower()
        url = page.url.lower()
        title = page.title().lower()

        # Check we're not on login/auth pages
        if "auth." in url or "/login" in url:
            # Exception: some services have /login in URL even when logged in
            if service_name not in ["jupyterhub"]:
                return False, "Still on login/auth page"

        # Service-specific verification
        verifications = {
            "grafana": ["dashboard", "home", "navigation", "grafana"],
            "jupyterhub": ["jupyter", "hub", "server", "spawn"],
            "planka": ["planka", "board", "project", "card"],
            "bookstack": ["bookstack", "shelf", "book", "chapter"],
            "vaultwarden": ["vault", "bitwarden", "cipher", "collection"],
            "homepage": ["services", "widget", "bookmark"],
            "open-webui": ["webui", "chat", "model", "conversation"],
            "seafile": ["seafile", "library", "file", "upload"],
            "portainer": ["portainer", "container", "stack", "environment"],
            "dockge": ["dockge", "compose", "terminal"],
            "homeassistant": ["home assistant", "dashboard", "overview", "lovelace"],
            "kopia": ["kopia", "snapshot", "backup", "repository"],
            "lam": ["ldap account manager", "tree view", "account", "login"],
            "litellm": ["litellm", "model", "api", "proxy"],
            "mastodon": ["mastodon", "timeline", "toot", "federated"],
            "matrix": ["element", "matrix", "room", "chat"],
            "onlyoffice": ["onlyoffice", "document", "spreadsheet", "presentation"],
            "sogo": ["sogo", "calendar", "mail", "address"],
            "clickhouse": ["clickhouse", "query", "database", "table"],
            "couchdb": ["couchdb", "fauxton", "database", "document"],
            "mail": ["roundcube", "webmail", "inbox", "compose"],
        }

        keywords = verifications.get(service_name, [service_name])

        # Check if any keyword appears in HTML or title
        found = any(keyword in html or keyword in title for keyword in keywords)

        if found:
            return True, f"Verified (found: {[k for k in keywords if k in html or k in title]})"
        else:
            return False, f"Keywords not found: {keywords}"

    except Exception as e:
        return False, f"Verification error: {e}"

def login_and_screenshot(service_name, service_url, playwright_instance):
    """Login to a service and take a verified screenshot"""
    print(f"\n{'='*70}")
    print(f"SERVICE: {service_name}")
    print(f"{'='*70}")

    # Create fresh browser and context for each service to avoid session conflicts
    browser = playwright_instance.firefox.launch(headless=True)
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    try:
        # Navigate to service
        print(f"1. Navigating to {service_url}")
        page.goto(service_url, timeout=30000)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   Current URL: {page.url}")

        # Check if we're on a login page with SSO button
        time.sleep(2)

        # Try to find and click SSO buttons on login pages
        if "/login" in page.url.lower() and "auth." not in page.url:
            print(f"2. On login page, looking for SSO button...")
            sso_clicked = False

            # Try various SSO button selectors
            sso_selectors = [
                "text=/Sign in with Authelia/i",
                "text=/Log in with SSO/i",
                "text=/SSO/i",
                "button:has-text('Authelia')",
                "a:has-text('SSO')",
            ]

            for selector in sso_selectors:
                try:
                    btn = page.locator(selector).first
                    if btn.is_visible(timeout=1000):
                        print(f"   Found SSO button: {selector}")
                        btn.click()
                        time.sleep(3)
                        page.wait_for_load_state("networkidle", timeout=15000)
                        sso_clicked = True
                        break
                except:
                    continue

            if not sso_clicked:
                print(f"   No SSO button found, may need direct login")

        # Handle Authelia login if redirected
        if "auth." in page.url:
            print(f"3. Handling Authelia authentication...")
            do_authelia_login(page, service_name)
            time.sleep(3)
            print(f"   Current URL: {page.url}")

            # After login, check if we're on consent page
            if "consent" in page.url.lower() or "Hi System Administrator" in page.content():
                print(f"4. On consent page, clicking ACCEPT...")
                try:
                    accept_btn = page.locator("button:has-text('ACCEPT')").or_(page.locator("button:has-text('Accept')"))
                    accept_btn.click(timeout=5000)
                    print(f"   Waiting for redirect after consent...")
                    time.sleep(10)
                    page.wait_for_load_state("networkidle", timeout=30000)
                    print(f"   After consent redirect: {page.url}")
                except Exception as e:
                    print(f"   Consent click error: {e}")
        else:
            print(f"3. Not on Authelia page")

        # For services with their own login (Vaultwarden)
        if service_name == "vaultwarden" and ("/login" in page.url or "#/login" in page.url):
            print(f"4. Logging into Vaultwarden directly...")
            try:
                # Wait for Angular app to load
                time.sleep(3)
                # Try to fill email field
                email_field = page.locator("input[type='email']").first
                email_field.wait_for(state="visible", timeout=5000)
                email_field.fill(f"{ADMIN_USER}@{DOMAIN}")
                time.sleep(1)
                # Click to next step (Vaultwarden may have multi-step login)
                page.keyboard.press("Tab")
                time.sleep(1)
                page.keyboard.press("Enter")
                time.sleep(2)
                # Now fill password if visible
                password_field = page.locator("input[type='password']").first
                password_field.wait_for(state="visible", timeout=5000)
                password_field.fill(ADMIN_PASSWORD)
                time.sleep(1)
                # Submit
                page.keyboard.press("Enter")
                time.sleep(5)
                page.wait_for_load_state("networkidle", timeout=15000)
            except Exception as e:
                print(f"   Vaultwarden login error: {e}")

        # Wait for page to fully load and any final redirects
        time.sleep(5)
        page.wait_for_load_state("networkidle", timeout=15000)

        # Verify we're logged in
        print(f"5. Verifying login status...")
        print(f"   Current URL: {page.url}")
        is_logged_in, verification_msg = verify_logged_in(page, service_name)

        if not is_logged_in:
            print(f"   ✗ NOT LOGGED IN: {verification_msg}")
            print(f"   Final URL: {page.url}")
            print(f"   Page title: {page.title()}")
            screenshot_path = f"{SCREENSHOT_DIR}/{service_name}_FAILED.png"
            page.screenshot(path=screenshot_path, full_page=True)
            print(f"   Screenshot saved: {screenshot_path}")
            return False

        print(f"   ✓ VERIFIED LOGGED IN: {verification_msg}")
        print(f"   Final URL: {page.url}")
        print(f"   Page title: {page.title()}")

        # Take screenshot
        screenshot_path = f"{SCREENSHOT_DIR}/{service_name}_SUCCESS.png"
        try:
            page.screenshot(path=screenshot_path, full_page=True)
        except Exception as ss_error:
            # If full page screenshot fails (page too large), take viewport screenshot
            print(f"   (Full page screenshot failed, taking viewport shot: {ss_error})")
            page.screenshot(path=screenshot_path, full_page=False)
        print(f"   ✓ Screenshot saved: {screenshot_path}")

        return True

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        screenshot_path = f"{SCREENSHOT_DIR}/{service_name}_ERROR.png"
        try:
            page.screenshot(path=screenshot_path, full_page=True)
        except:
            page.screenshot(path=screenshot_path, full_page=False)
        print(f"   Screenshot saved: {screenshot_path}")
        return False

    finally:
        page.close()
        context.close()
        browser.close()

if __name__ == "__main__":
    # Services to test (focusing on OIDC services first for debugging)
    test_mode = os.getenv('TEST_MODE', 'all')

    if test_mode == 'oidc':
        services = [
            ("grafana", f"https://grafana.{DOMAIN}"),
            ("jupyterhub", f"https://jupyterhub.{DOMAIN}"),
            ("planka", f"https://planka.{DOMAIN}"),
        ]
    elif test_mode == 'remaining':
        services = [
            ("homeassistant", f"https://homeassistant.{DOMAIN}"),
            ("kopia", f"https://kopia.{DOMAIN}"),
            ("lam", f"https://lam.{DOMAIN}"),
            ("litellm", f"https://litellm.{DOMAIN}"),
            ("mastodon", f"https://mastodon.{DOMAIN}"),
            ("matrix", f"https://matrix.{DOMAIN}"),
            ("onlyoffice", f"https://onlyoffice.{DOMAIN}"),
            ("sogo", f"https://sogo.{DOMAIN}"),
            ("clickhouse", f"https://clickhouse.{DOMAIN}"),
            ("couchdb", f"https://couchdb.{DOMAIN}"),
            ("mail", f"https://mail.{DOMAIN}"),
        ]
    else:
        services = [
            ("grafana", f"https://grafana.{DOMAIN}"),
            ("jupyterhub", f"https://jupyterhub.{DOMAIN}"),
            ("planka", f"https://planka.{DOMAIN}"),
            ("bookstack", f"https://bookstack.{DOMAIN}"),
            ("vaultwarden", f"https://vaultwarden.{DOMAIN}"),
            ("homepage", f"https://homepage.{DOMAIN}"),
            ("open-webui", f"https://open-webui.{DOMAIN}"),
            ("seafile", f"https://seafile.{DOMAIN}"),
            ("portainer", f"https://portainer.{DOMAIN}"),
            ("dockge", f"https://dockge.{DOMAIN}"),
        ]

    print("\n" + "="*70)
    print("COMPREHENSIVE LOGIN & SCREENSHOT TEST")
    print("="*70)
    print(f"Domain: {DOMAIN}")
    print(f"User: {ADMIN_USER}")
    print(f"Services: {len(services)}")
    print(f"Screenshots: {SCREENSHOT_DIR}")
    print("="*70)

    results = {}

    with sync_playwright() as p:
        for service_name, service_url in services:
            results[service_name] = login_and_screenshot(service_name, service_url, p)
            time.sleep(2)  # Brief pause between services

    # Summary
    print("\n" + "="*70)
    print("FINAL RESULTS")
    print("="*70)

    for service_name, success in results.items():
        status = "✓ SUCCESS" if success else "✗ FAILED"
        print(f"{status} - {service_name}")

    passed = sum(1 for v in results.values() if v)
    total = len(results)
    print(f"\n{passed}/{total} services verified and screenshotted ({passed*100//total}%)")
    print(f"\nScreenshots saved to: {SCREENSHOT_DIR}")
    print("="*70 + "\n")

    sys.exit(0 if passed == total else 1)
