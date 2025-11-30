#!/usr/bin/env python3
"""
Test Authelia login functionality using playwright service directly.
This bypasses the gradle build issue with kfuncdb.

Hardcoded Authelia selectors:
- Username: #username-textfield
- Password: #password-textfield
- Submit: #sign-in-button
"""

import sys
import base64
import time
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

def test_authelia_login(url: str, username: str, password: str, service_name: str = None):
    """
    Navigate to a protected URL, login via Authelia, and take screenshot.

    Args:
        url: Target URL (will redirect to Authelia if not authenticated)
        username: Authelia username
        password: Authelia password
        service_name: Optional service name for screenshot storage

    Returns:
        dict with status, finalUrl, loginDetected, screenshot path
    """
    print(f"🔐 Testing login for: {url}")

    with sync_playwright() as p:
        browser = p.firefox.launch()
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()

        start = time.time()

        try:
            # Navigate to protected URL
            print(f"  → Navigating to {url}")
            page.goto(url, timeout=30000, wait_until='networkidle')

            # Check if we landed on Authelia login page
            username_input = page.query_selector('#username-textfield')
            login_detected = username_input is not None

            if login_detected:
                print(f"  ✓ Authelia login page detected")

                # Fill credentials
                print(f"  → Filling username: {username}")
                page.fill('#username-textfield', username)

                print(f"  → Filling password: {'*' * len(password)}")
                page.fill('#password-textfield', password)

                # Click submit
                print(f"  → Clicking sign-in button")
                page.click('#sign-in-button')

                # Wait for navigation after login
                try:
                    page.wait_for_load_state('networkidle', timeout=30000)
                    print(f"  ✓ Login completed")
                except PlaywrightTimeout:
                    print(f"  ⚠ Timeout waiting for post-login navigation")
            else:
                print(f"  ✓ Already authenticated (no login page)")

            # Get final URL
            final_url = page.url
            print(f"  → Final URL: {final_url}")

            # Take screenshot
            screenshot_path = f"/tmp/{service_name or 'test'}-login-{int(time.time())}.png"
            page.screenshot(path=screenshot_path)
            screenshot_size = len(open(screenshot_path, 'rb').read()) / 1024

            elapsed = time.time() - start
            print(f"  ✓ Screenshot saved: {screenshot_path} ({screenshot_size:.1f}KB)")
            print(f"  ⏱ Total time: {elapsed:.2f}s")

            browser.close()

            return {
                'success': True,
                'finalUrl': final_url,
                'loginDetected': login_detected,
                'screenshotPath': screenshot_path,
                'screenshotSizeKb': screenshot_size,
                'elapsedSeconds': elapsed
            }

        except Exception as e:
            elapsed = time.time() - start
            print(f"  ✗ Error: {e}")
            print(f"  ⏱ Failed after: {elapsed:.2f}s")

            browser.close()

            return {
                'success': False,
                'error': str(e),
                'elapsedSeconds': elapsed
            }

if __name__ == '__main__':
    # Test configuration
    TEST_SERVICES = [
        ('https://grafana.project-saturn.com', 'grafana'),
        ('https://planka.project-saturn.com', 'planka'),
        ('https://outline.project-saturn.com', 'outline'),
    ]

    # Get credentials from environment or use defaults
    import os
    username = os.getenv('AUTHELIA_USERNAME', 'admin')
    password = os.getenv('AUTHELIA_PASSWORD', 'password')

    print(f"\n{'='*60}")
    print(f"Authelia Login Test")
    print(f"Username: {username}")
    print(f"{'='*60}\n")

    results = []
    for url, service_name in TEST_SERVICES:
        result = test_authelia_login(url, username, password, service_name)
        results.append((service_name, result))
        print()

    # Summary
    print(f"\n{'='*60}")
    print(f"Summary")
    print(f"{'='*60}")
    successful = sum(1 for _, r in results if r['success'])
    print(f"✅ Successful: {successful}/{len(results)}")

    for service_name, result in results:
        status = "✅" if result['success'] else "✗"
        if result['success']:
            print(f"  {status} {service_name}: {result['finalUrl']}")
        else:
            print(f"  {status} {service_name}: {result['error']}")

    sys.exit(0 if successful == len(results) else 1)
