#!/usr/bin/env python3
"""
Automated OIDC Login Flow Test
Tests complete authentication flow: Grafana -> Dex -> LDAP -> Grafana
"""

import time
import sys
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.options import Options

def test_oidc_login():
    print("=" * 60)
    print("     AUTOMATED OIDC LOGIN FLOW TEST")
    print("=" * 60)
    print()

    # Configure Chrome options
    chrome_options = Options()
    chrome_options.add_argument('--headless')
    chrome_options.add_argument('--no-sandbox')
    chrome_options.add_argument('--disable-dev-shm-usage')
    chrome_options.add_argument('--disable-gpu')
    chrome_options.add_argument('--window-size=1920,1080')
    chrome_options.add_argument('--ignore-certificate-errors')

    # Connect to browserless
    driver = webdriver.Remote(
        command_executor='http://chrome:4444',
        options=chrome_options
    )

    try:
        # Step 1: Load Grafana
        print("[1/6] Loading Grafana...")
        driver.get('http://grafana.lab.localhost:3000')
        time.sleep(2)
        print(f"      ✓ URL: {driver.current_url}")
        print(f"      ✓ Title: {driver.title}")

        # Step 2: Find and click OAuth login button
        print("\n[2/6] Looking for OAuth login button...")
        wait = WebDriverWait(driver, 10)
        oauth_button = wait.until(
            EC.presence_of_element_located((By.XPATH, "//a[contains(@href, 'generic_oauth')]"))
        )
        print(f"      ✓ Found OAuth button: {oauth_button.text}")

        # Step 3: Click OAuth button
        print("\n[3/6] Clicking OAuth login...")
        oauth_button.click()
        time.sleep(2)

        current_url = driver.current_url
        print(f"      ✓ Redirected to: {current_url}")

        if 'dex' not in current_url:
            raise Exception(f"Not redirected to Dex! URL: {current_url}")
        print("      ✓ On Dex login page")

        # Step 4: Fill in credentials
        print("\n[4/6] Filling in credentials...")
        username_field = wait.until(
            EC.presence_of_element_located((By.NAME, "login"))
        )
        password_field = driver.find_element(By.NAME, "password")

        username_field.send_keys("authtest")
        password_field.send_keys("TestAuth123!")
        print("      ✓ Credentials entered")

        # Step 5: Submit form
        print("\n[5/6] Submitting login form...")
        submit_button = driver.find_element(By.XPATH, "//button[@type='submit']")
        submit_button.click()

        # Wait for redirect back to Grafana
        time.sleep(3)
        final_url = driver.current_url
        print(f"      ✓ Final URL: {final_url}")

        if 'grafana' not in final_url:
            raise Exception(f"Not redirected back to Grafana! URL: {final_url}")

        # Step 6: Verify we're logged in
        print("\n[6/6] Verifying logged in state...")
        time.sleep(2)

        page_source = driver.page_source.lower()

        # Check if we're NOT on login page
        has_password_field = 'type="password"' in page_source
        has_signin = 'sign in' in page_source and has_password_field

        if has_signin:
            print("      ❌ Still on login page!")
            raise Exception("Login failed - still showing login form")

        print("      ✓ Not on login page (logged in!)")
        print(f"      ✓ Page title: {driver.title}")

        # Take screenshot
        screenshot = driver.get_screenshot_as_png()
        with open('/tmp/grafana-logged-in.png', 'wb') as f:
            f.write(screenshot)
        print("      ✓ Screenshot saved to /tmp/grafana-logged-in.png")

        # Success!
        print()
        print("╔" + "═" * 58 + "╗")
        print("║" + " " * 15 + "✅ OIDC LOGIN TEST PASSED!" + " " * 15 + "║")
        print("╚" + "═" * 58 + "╝")
        print()
        print("Verified:")
        print("  • Grafana loaded successfully ✓")
        print("  • OAuth button found and clicked ✓")
        print("  • Redirected to Dex ✓")
        print("  • Credentials submitted ✓")
        print("  • Redirected back to Grafana ✓")
        print("  • Successfully logged in ✓")
        print()

        return True

    except Exception as e:
        print()
        print("╔" + "═" * 58 + "╗")
        print("║" + " " * 18 + "❌ TEST FAILED" + " " * 19 + "║")
        print("╚" + "═" * 58 + "╝")
        print()
        print(f"Error: {str(e)}")
        print(f"Current URL: {driver.current_url}")
        print(f"Page title: {driver.title}")

        # Save error screenshot
        try:
            screenshot = driver.get_screenshot_as_png()
            with open('/tmp/error-screenshot.png', 'wb') as f:
                f.write(screenshot)
            print("Error screenshot saved to /tmp/error-screenshot.png")
        except:
            pass

        return False

    finally:
        driver.quit()

if __name__ == "__main__":
    try:
        success = test_oidc_login()
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"Fatal error: {e}")
        sys.exit(1)
