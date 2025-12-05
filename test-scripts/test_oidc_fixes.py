#!/usr/bin/env python3
"""Test OIDC fixes for Grafana, JupyterHub, and Planka"""
import os
import sys
import time
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

DOMAIN = os.getenv('DOMAIN', 'project-saturn.com')
ADMIN_USER = os.getenv('ADMIN_USER', 'admin')
ADMIN_PASSWORD = os.getenv('ADMIN_PASS', 'dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV')

def test_grafana(browser):
    """Test Grafana OIDC login"""
    print("\n" + "="*70)
    print("TEST 1: Grafana OIDC Authentication")
    print("="*70)

    page = browser.new_page()
    try:
        url = f"https://grafana.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        page.wait_for_load_state("networkidle", timeout=10000)
        print(f"   Current URL: {page.url}")

        # Look for SSO button
        print(f"2. Looking for 'Sign in with Authelia' button")
        try:
            sso_button = page.locator("text=/Sign in with Authelia/i")
            sso_button.wait_for(timeout=5000)
            print(f"   ✓ Found SSO button")
        except PlaywrightTimeout:
            print(f"   ✗ SSO button not found")
            print(f"   Page title: {page.title()}")
            return False

        # Click SSO button
        print(f"3. Clicking SSO button")
        sso_button.click()
        time.sleep(2)
        page.wait_for_load_state("networkidle", timeout=10000)
        print(f"   Redirected to: {page.url}")

        # Should be on Authelia
        if "auth." not in page.url:
            print(f"   ✗ Not redirected to Authelia")
            return False
        print(f"   ✓ On Authelia login page")

        # Login
        print(f"4. Logging in to Authelia")
        page.fill("input#username-textfield", ADMIN_USER)
        page.fill("input#password-textfield", ADMIN_PASSWORD)
        page.click("button#sign-in-button")
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   Current URL: {page.url}")

        # Handle consent if needed
        if "consent" in page.url.lower() or page.locator("button:has-text('Accept')").count() > 0:
            print(f"5. Accepting consent")
            page.click("button:has-text('Accept')")
            time.sleep(3)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   Current URL: {page.url}")

        # Check final state
        print(f"6. Verifying login success")
        time.sleep(2)

        if "grafana." in page.url and "/login" not in page.url:
            print(f"   ✓ SUCCESS - Logged into Grafana!")
            print(f"   Final URL: {page.url}")
            print(f"   Page title: {page.title()}")
            return True
        elif "/login" in page.url:
            print(f"   ✗ FAILED - Token exchange error (still on login page)")
            print(f"   Final URL: {page.url}")
            return False
        else:
            print(f"   ✗ FAILED - Unexpected URL")
            print(f"   Final URL: {page.url}")
            return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        return False
    finally:
        page.close()


def test_jupyterhub(browser):
    """Test JupyterHub auto-redirect"""
    print("\n" + "="*70)
    print("TEST 2: JupyterHub Auto-Redirect to Authelia")
    print("="*70)

    page = browser.new_page()
    try:
        url = f"https://jupyterhub.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        time.sleep(2)
        page.wait_for_load_state("networkidle", timeout=10000)
        print(f"   Current URL: {page.url}")

        # Should auto-redirect to Authelia
        print(f"2. Checking for auto-redirect to Authelia")
        if "auth." in page.url:
            print(f"   ✓ Auto-redirected to Authelia!")
        elif "/hub/login" in page.url:
            print(f"   ✗ FAILED - Still on JupyterHub login page (no auto-redirect)")
            print(f"   Page title: {page.title()}")
            return False
        else:
            print(f"   ? On URL: {page.url}")
            # Try to wait a bit more
            time.sleep(2)
            if "auth." not in page.url:
                print(f"   ✗ FAILED - Not redirected to Authelia")
                return False
            print(f"   ✓ Auto-redirected to Authelia (after delay)")

        # Login
        print(f"3. Logging in to Authelia")
        page.fill("input#username-textfield", ADMIN_USER)
        page.fill("input#password-textfield", ADMIN_PASSWORD)
        page.click("button#sign-in-button")
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   Current URL: {page.url}")

        # Handle consent
        if "consent" in page.url.lower() or page.locator("button:has-text('Accept')").count() > 0:
            print(f"4. Accepting consent")
            page.click("button:has-text('Accept')")
            time.sleep(3)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   Current URL: {page.url}")

        # Check success
        print(f"5. Verifying login success")
        time.sleep(2)

        if "jupyterhub." in page.url and "/hub/login" not in page.url:
            print(f"   ✓ SUCCESS - Logged into JupyterHub!")
            print(f"   Final URL: {page.url}")
            return True
        else:
            print(f"   ✗ FAILED")
            print(f"   Final URL: {page.url}")
            return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        return False
    finally:
        page.close()


def test_planka(browser):
    """Test Planka SSO button"""
    print("\n" + "="*70)
    print("TEST 3: Planka SSO Button")
    print("="*70)

    page = browser.new_page()
    try:
        url = f"https://planka.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        page.wait_for_load_state("networkidle", timeout=10000)
        print(f"   Current URL: {page.url}")

        # Look for SSO button
        print(f"2. Looking for SSO button")
        try:
            # Try various selectors
            sso_button = None
            selectors = [
                "text=/Sign in with SSO/i",
                "text=/SSO/i",
                "button:has-text('SSO')",
                "a:has-text('SSO')"
            ]

            for selector in selectors:
                try:
                    btn = page.locator(selector)
                    if btn.count() > 0:
                        sso_button = btn.first
                        print(f"   ✓ Found SSO button with selector: {selector}")
                        break
                except:
                    continue

            if not sso_button:
                print(f"   ✗ SSO button not found")
                print(f"   Page title: {page.title()}")
                # Take a look at the page content
                body_text = page.text_content("body")
                if "sso" in body_text.lower():
                    print(f"   (But 'SSO' text found in page content)")
                return False

        except Exception as e:
            print(f"   ✗ SSO button not found: {e}")
            return False

        # Click SSO button
        print(f"3. Clicking SSO button")
        sso_button.click()
        time.sleep(2)
        page.wait_for_load_state("networkidle", timeout=10000)
        print(f"   Redirected to: {page.url}")

        # Should be on Authelia
        if "auth." not in page.url:
            print(f"   ✗ Not redirected to Authelia")
            return False
        print(f"   ✓ Redirected to Authelia")

        # Login
        print(f"4. Logging in to Authelia")
        page.fill("input#username-textfield", ADMIN_USER)
        page.fill("input#password-textfield", ADMIN_PASSWORD)
        page.click("button#sign-in-button")
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   Current URL: {page.url}")

        # Handle consent
        if "consent" in page.url.lower() or page.locator("button:has-text('Accept')").count() > 0:
            print(f"5. Accepting consent")
            page.click("button:has-text('Accept')")
            time.sleep(3)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   Current URL: {page.url}")

        # Check success
        print(f"6. Verifying login success")
        time.sleep(2)

        if "planka." in page.url and "login" not in page.url.lower():
            print(f"   ✓ SUCCESS - Logged into Planka!")
            print(f"   Final URL: {page.url}")
            return True
        else:
            print(f"   ✗ FAILED")
            print(f"   Final URL: {page.url}")
            return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        return False
    finally:
        page.close()


if __name__ == "__main__":
    print("\n" + "="*70)
    print("OIDC AUTHENTICATION FIXES - VERIFICATION TEST")
    print("="*70)
    print(f"Domain: {DOMAIN}")
    print(f"User: {ADMIN_USER}")
    print("="*70)

    results = {}

    with sync_playwright() as p:
        browser = p.firefox.launch(headless=True)
        context = browser.new_context(ignore_https_errors=True)

        results['grafana'] = test_grafana(context)
        time.sleep(2)

        results['jupyterhub'] = test_jupyterhub(context)
        time.sleep(2)

        results['planka'] = test_planka(context)

        browser.close()

    # Summary
    print("\n" + "="*70)
    print("FINAL RESULTS")
    print("="*70)

    for service, result in results.items():
        status = "✓ PASS" if result else "✗ FAIL"
        print(f"{status} - {service}")

    passed = sum(1 for v in results.values() if v)
    total = len(results)
    print(f"\n{passed}/{total} tests passed ({passed*100//total}%)")
    print("="*70 + "\n")

    sys.exit(0 if passed == total else 1)
