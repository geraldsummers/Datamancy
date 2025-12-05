#!/usr/bin/env python3
"""Test remaining services with credential inputs"""
import os
import sys
import time
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

DOMAIN = os.getenv('DOMAIN', 'project-saturn.com')
ADMIN_USER = os.getenv('ADMIN_USER', 'admin')
ADMIN_PASSWORD = os.getenv('ADMIN_PASS')
LDAP_PASSWORD = os.getenv('LDAP_PASS')
SCREENSHOT_DIR = os.getenv('SCREENSHOT_DIR', '/screenshots')

def handle_authelia_if_needed(page):
    """Handle Authelia login if we're redirected there"""
    try:
        if "auth." in page.url:
            print(f"   → On Authelia, logging in")
            page.fill("input#username-textfield", ADMIN_USER, timeout=5000)
            page.fill("input#password-textfield", ADMIN_PASSWORD, timeout=5000)
            page.click("button#sign-in-button")
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=20000)

            # Handle consent if present
            if "consent" in page.url.lower():
                print(f"   → Accepting consent")
                page.click("button:has-text('ACCEPT'), button:has-text('Accept')")
                time.sleep(10)
                page.wait_for_load_state("networkidle", timeout=30000)

            print(f"   → After Authelia: {page.url}")
            return True
        return False
    except Exception as e:
        print(f"   → Authelia error: {e}")
        return False

def test_bookstack(p):
    """Test BookStack with LDAP login"""
    print("\n" + "="*70)
    print("Testing BookStack (LDAP)")
    print("="*70)

    browser = p.firefox.launch(headless=True)
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    try:
        url = f"https://bookstack.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   URL: {page.url}")

        # Handle Authelia if redirected
        handle_authelia_if_needed(page)
        time.sleep(3)

        # Should be on BookStack login after Authelia passes
        if "login" in page.url.lower() or "Log In" in page.content():
            print(f"2. On BookStack login page, entering LDAP credentials")
            # Fill username
            username_field = page.locator("input[name='username'], input[id='username'], input[type='text']").first
            username_field.fill(ADMIN_USER, timeout=5000)
            time.sleep(1)

            # Fill password
            password_field = page.locator("input[name='password'], input[type='password']").first
            password_field.fill(LDAP_PASSWORD, timeout=5000)
            time.sleep(1)

            # Click login button
            login_btn = page.locator("button:has-text('Log In'), button[type='submit']").first
            login_btn.click()
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   After login URL: {page.url}")

            # Check if logged in
            if "bookstack" in page.content().lower() and "/login" not in page.url:
                print(f"   ✓ SUCCESS - Logged into BookStack")
                page.screenshot(path=f"{SCREENSHOT_DIR}/bookstack_SUCCESS.png", full_page=False)
                return True
            else:
                print(f"   ✗ FAILED - Login unsuccessful")
                page.screenshot(path=f"{SCREENSHOT_DIR}/bookstack_FAILED.png", full_page=False)
                return False
        else:
            print(f"2. Not on login page, checking if already authenticated")
            if "bookstack" in page.content().lower():
                print(f"   ✓ SUCCESS - Already authenticated")
                page.screenshot(path=f"{SCREENSHOT_DIR}/bookstack_SUCCESS.png", full_page=False)
                return True

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        page.screenshot(path=f"{SCREENSHOT_DIR}/bookstack_ERROR.png", full_page=False)
        return False
    finally:
        context.close()
        browser.close()

def test_vaultwarden(p):
    """Test Vaultwarden OIDC"""
    print("\n" + "="*70)
    print("Testing Vaultwarden (OIDC)")
    print("="*70)

    browser = p.firefox.launch(headless=True)
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    try:
        url = f"https://vaultwarden.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        time.sleep(5)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   URL: {page.url}")

        # Look for SSO button
        print(f"2. Looking for SSO button")
        sso_btn = page.locator("button:has-text('SSO'), a:has-text('SSO')").first
        if sso_btn.is_visible(timeout=3000):
            print(f"   Found SSO button, clicking")
            sso_btn.click()
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=20000)
            print(f"   After SSO click URL: {page.url}")

            # Should be on Authelia
            if "auth." in page.url:
                print(f"3. On Authelia, logging in")
                page.fill("input#username-textfield", ADMIN_USER)
                page.fill("input#password-textfield", ADMIN_PASSWORD)
                page.click("button#sign-in-button")
                time.sleep(5)
                page.wait_for_load_state("networkidle", timeout=20000)

                # Handle consent
                if "consent" in page.url.lower():
                    print(f"4. Accepting consent")
                    page.click("button:has-text('ACCEPT')")
                    time.sleep(10)
                    page.wait_for_load_state("networkidle", timeout=30000)

                print(f"   Final URL: {page.url}")
                if "vaultwarden" in page.url and "#/login" not in page.url:
                    print(f"   ✓ SUCCESS - Logged into Vaultwarden")
                    page.screenshot(path=f"{SCREENSHOT_DIR}/vaultwarden_SUCCESS.png", full_page=False)
                    return True
                else:
                    print(f"   ✗ FAILED - Still on login")
                    page.screenshot(path=f"{SCREENSHOT_DIR}/vaultwarden_FAILED.png", full_page=False)
                    return False
        else:
            print(f"   ✗ No SSO button found")
            page.screenshot(path=f"{SCREENSHOT_DIR}/vaultwarden_FAILED.png", full_page=False)
            return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        page.screenshot(path=f"{SCREENSHOT_DIR}/vaultwarden_ERROR.png", full_page=False)
        return False
    finally:
        context.close()
        browser.close()

def test_kopia(p):
    """Test Kopia with credentials"""
    print("\n" + "="*70)
    print("Testing Kopia")
    print("="*70)

    browser = p.firefox.launch(headless=True)
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    try:
        url = f"https://kopia.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   URL: {page.url}")

        # Handle Authelia if redirected
        handle_authelia_if_needed(page)
        time.sleep(3)

        # Authelia should pass, then Kopia login
        if "Missing credentials" in page.content() or "username" in page.content().lower():
            print(f"2. On Kopia login, entering credentials")
            page.fill("input[name='username'], input[type='text']", ADMIN_USER)
            page.fill("input[name='password'], input[type='password']", ADMIN_PASSWORD)
            time.sleep(1)
            page.click("button[type='submit'], button:has-text('Login')")
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   After login URL: {page.url}")

            if "kopia" in page.content().lower() and "credentials" not in page.content().lower():
                print(f"   ✓ SUCCESS - Logged into Kopia")
                page.screenshot(path=f"{SCREENSHOT_DIR}/kopia_SUCCESS.png", full_page=False)
                return True
            else:
                print(f"   ✗ FAILED")
                page.screenshot(path=f"{SCREENSHOT_DIR}/kopia_FAILED.png", full_page=False)
                return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        page.screenshot(path=f"{SCREENSHOT_DIR}/kopia_ERROR.png", full_page=False)
        return False
    finally:
        context.close()
        browser.close()

def test_lam(p):
    """Test LAM with LDAP credentials"""
    print("\n" + "="*70)
    print("Testing LAM (LDAP Account Manager)")
    print("="*70)

    browser = p.firefox.launch(headless=True)
    context = browser.new_context(ignore_https_errors=True)
    page = context.new_page()

    try:
        url = f"https://lam.{DOMAIN}"
        print(f"1. Navigating to {url}")
        page.goto(url, timeout=30000)
        time.sleep(3)
        page.wait_for_load_state("networkidle", timeout=15000)
        print(f"   URL: {page.url}")

        # Handle Authelia if redirected
        handle_authelia_if_needed(page)
        time.sleep(3)

        if "/login" in page.url.lower():
            print(f"2. On LAM login page, entering credentials")
            # LAM uses cn=admin,dc=...
            page.fill("input[name='username']", f"cn=admin,dc={DOMAIN.replace('.', ',dc=')}")
            page.fill("input[name='password']", LDAP_PASSWORD)
            time.sleep(1)
            page.click("button[type='submit'], input[type='submit']")
            time.sleep(5)
            page.wait_for_load_state("networkidle", timeout=15000)
            print(f"   After login URL: {page.url}")

            if "login" not in page.url.lower() and "lam" in page.content().lower():
                print(f"   ✓ SUCCESS - Logged into LAM")
                page.screenshot(path=f"{SCREENSHOT_DIR}/lam_SUCCESS.png", full_page=False)
                return True
            else:
                print(f"   ✗ FAILED")
                page.screenshot(path=f"{SCREENSHOT_DIR}/lam_FAILED.png", full_page=False)
                return False

    except Exception as e:
        print(f"   ✗ ERROR: {e}")
        page.screenshot(path=f"{SCREENSHOT_DIR}/lam_ERROR.png", full_page=False)
        return False
    finally:
        context.close()
        browser.close()

if __name__ == "__main__":
    print("\n" + "="*70)
    print("TESTING REMAINING SERVICES WITH CREDENTIALS")
    print("="*70)

    with sync_playwright() as p:
        results = {
            'bookstack': test_bookstack(p),
            'vaultwarden': test_vaultwarden(p),
            'kopia': test_kopia(p),
            'lam': test_lam(p),
        }

    print("\n" + "="*70)
    print("RESULTS")
    print("="*70)
    for service, success in results.items():
        status = "✓ SUCCESS" if success else "✗ FAILED"
        print(f"{status} - {service}")

    passed = sum(1 for v in results.values() if v)
    print(f"\n{passed}/{len(results)} services successful")
    print("="*70)
