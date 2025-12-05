#!/usr/bin/env python3
"""
Browser automation script to test login for all UI services
Uses the Playwright service via HTTP API
"""
import os
import sys
import json
import time
import requests
from datetime import datetime

# Service configuration
DOMAIN = "project-saturn.com"
ADMIN_USER = "admin"
ADMIN_PASSWORD = "dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV"
SCREENSHOT_DIR = os.path.expanduser("~/datamancyscreenshots")

# UI Services to test (extracted from docker-compose.yml)
SERVICES = [
    {
        "name": "Authelia (SSO)",
        "url": f"https://auth.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input#username",
        "password_selector": "input[name='password'], input#password, input[type='password']",
        "submit_selector": "button[type='submit'], input[type='submit']",
    },
    {
        "name": "Grafana",
        "url": f"https://grafana.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='user'], input[name='username']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit'], button[aria-label='Login button']",
    },
    {
        "name": "Open WebUI",
        "url": f"https://open-webui.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='email'], input[type='email']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Vaultwarden",
        "url": f"https://vaultwarden.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='email'], input[type='email']",
        "password_selector": "input[name='masterPassword'], input[id='masterPassword']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Planka",
        "url": f"https://planka.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='emailOrUsername'], input[name='username']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Bookstack",
        "url": f"https://bookstack.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input[name='email']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "JupyterHub",
        "url": f"https://jupyterhub.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input#username_input",
        "password_selector": "input[name='password'], input#password_input",
        "submit_selector": "button[type='submit'], input[type='submit']",
    },
    {
        "name": "Homepage",
        "url": f"https://homepage.{DOMAIN}",
        "login_required": False,
    },
    {
        "name": "Seafile",
        "url": f"https://seafile.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='login'], input[type='text']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit'], input[type='submit']",
    },
    {
        "name": "OnlyOffice",
        "url": f"https://onlyoffice.{DOMAIN}",
        "login_required": False,
    },
    {
        "name": "SOGo (Webmail)",
        "url": f"https://sogo.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='userName'], input#userName",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit'], input[type='submit']",
    },
    {
        "name": "Home Assistant",
        "url": f"https://homeassistant.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input[type='text']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "LiteLLM",
        "url": f"https://litellm.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input[type='text']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Kopia",
        "url": f"https://kopia.{DOMAIN}",
        "login_required": False,
    },
    {
        "name": "Portainer",
        "url": f"https://portainer.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username']",
        "password_selector": "input[name='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Mailu Admin",
        "url": f"https://mail.{DOMAIN}/admin",
        "login_required": True,
        "username_selector": "input[name='email'], input[type='email']",
        "password_selector": "input[name='pw'], input[name='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Roundcube (Webmail)",
        "url": f"https://mail.{DOMAIN}/webmail",
        "login_required": True,
        "username_selector": "input[name='_user'], input#rcmloginuser",
        "password_selector": "input[name='_pass'], input#rcmloginpwd",
        "submit_selector": "button[type='submit'], input#rcmloginsubmit",
    },
    {
        "name": "LDAP Account Manager",
        "url": f"https://lam.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input#username",
        "password_selector": "input[name='passwd'], input#passwd",
        "submit_selector": "button[type='submit'], input[type='submit']",
    },
    {
        "name": "Dockge",
        "url": f"https://dockge.{DOMAIN}",
        "login_required": True,
        "username_selector": "input[name='username'], input[type='text']",
        "password_selector": "input[name='password'], input[type='password']",
        "submit_selector": "button[type='submit']",
    },
    {
        "name": "Mastodon",
        "url": f"https://mastodon.{DOMAIN}",
        "login_required": False,
    },
]


def test_service(browser, service, results):
    """Test a single service and capture screenshots"""
    service_name = service["name"]
    url = service["url"]

    print(f"\n{'='*60}")
    print(f"Testing: {service_name}")
    print(f"URL: {url}")
    print('='*60)

    result = {
        "service": service_name,
        "url": url,
        "timestamp": datetime.now().isoformat(),
        "accessible": False,
        "login_attempted": False,
        "login_successful": False,
        "error": None,
        "screenshots": []
    }

    try:
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()

        # Navigate to service
        print(f"Navigating to {url}...")
        try:
            response = page.goto(url, timeout=30000, wait_until="domcontentloaded")
            result["accessible"] = response.status < 400
            print(f"Status: {response.status}")
        except PlaywrightTimeout:
            result["error"] = "Navigation timeout"
            print("ERROR: Navigation timeout")
            context.close()
            return result
        except Exception as e:
            result["error"] = f"Navigation error: {str(e)}"
            print(f"ERROR: {str(e)}")
            context.close()
            return result

        # Take initial screenshot
        screenshot_path = os.path.join(SCREENSHOT_DIR, f"{service_name.replace(' ', '_')}_01_initial.png")
        page.screenshot(path=screenshot_path, full_page=True)
        result["screenshots"].append(screenshot_path)
        print(f"Screenshot saved: {screenshot_path}")

        # Wait a bit for dynamic content
        page.wait_for_timeout(2000)

        # Take post-load screenshot
        screenshot_path = os.path.join(SCREENSHOT_DIR, f"{service_name.replace(' ', '_')}_02_loaded.png")
        page.screenshot(path=screenshot_path, full_page=True)
        result["screenshots"].append(screenshot_path)
        print(f"Screenshot saved: {screenshot_path}")

        # Attempt login if required
        if service.get("login_required", False):
            print("Login required, attempting login...")
            result["login_attempted"] = True

            try:
                # Find username field
                username_selector = service.get("username_selector", "")
                password_selector = service.get("password_selector", "")
                submit_selector = service.get("submit_selector", "")

                # Try to find login form elements
                username_input = None
                for selector in username_selector.split(","):
                    selector = selector.strip()
                    try:
                        username_input = page.wait_for_selector(selector, timeout=5000)
                        print(f"Found username input: {selector}")
                        break
                    except:
                        continue

                if not username_input:
                    result["error"] = "Could not find username input"
                    print("ERROR: Could not find username input")
                    # Take screenshot of login page
                    screenshot_path = os.path.join(SCREENSHOT_DIR, f"{service_name.replace(' ', '_')}_03_login_page.png")
                    page.screenshot(path=screenshot_path, full_page=True)
                    result["screenshots"].append(screenshot_path)
                    context.close()
                    return result

                # Find password field
                password_input = None
                for selector in password_selector.split(","):
                    selector = selector.strip()
                    try:
                        password_input = page.wait_for_selector(selector, timeout=5000)
                        print(f"Found password input: {selector}")
                        break
                    except:
                        continue

                if not password_input:
                    result["error"] = "Could not find password input"
                    print("ERROR: Could not find password input")
                    context.close()
                    return result

                # Fill in credentials
                print(f"Filling username: {ADMIN_USER}")
                username_input.fill(ADMIN_USER)
                page.wait_for_timeout(500)

                print("Filling password...")
                password_input.fill(ADMIN_PASSWORD)
                page.wait_for_timeout(500)

                # Take screenshot before submit
                screenshot_path = os.path.join(SCREENSHOT_DIR, f"{service_name.replace(' ', '_')}_03_before_submit.png")
                page.screenshot(path=screenshot_path, full_page=True)
                result["screenshots"].append(screenshot_path)
                print(f"Screenshot saved: {screenshot_path}")

                # Find and click submit button
                submit_button = None
                for selector in submit_selector.split(","):
                    selector = selector.strip()
                    try:
                        submit_button = page.wait_for_selector(selector, timeout=5000)
                        print(f"Found submit button: {selector}")
                        break
                    except:
                        continue

                if submit_button:
                    print("Clicking submit...")
                    submit_button.click()

                    # Wait for navigation or response
                    page.wait_for_timeout(5000)

                    # Take post-login screenshot
                    screenshot_path = os.path.join(SCREENSHOT_DIR, f"{service_name.replace(' ', '_')}_04_after_login.png")
                    page.screenshot(path=screenshot_path, full_page=True)
                    result["screenshots"].append(screenshot_path)
                    print(f"Screenshot saved: {screenshot_path}")

                    # Check if still on login page (login failed) or moved (success)
                    current_url = page.url
                    if "login" in current_url.lower() and current_url == url:
                        result["login_successful"] = False
                        result["error"] = "Still on login page after submit"
                        print("WARNING: Still on login page, login may have failed")
                    else:
                        result["login_successful"] = True
                        print("SUCCESS: Login appears successful")
                else:
                    result["error"] = "Could not find submit button"
                    print("ERROR: Could not find submit button")

            except Exception as e:
                result["error"] = f"Login error: {str(e)}"
                print(f"ERROR during login: {str(e)}")

        context.close()

    except Exception as e:
        result["error"] = f"Unexpected error: {str(e)}"
        print(f"ERROR: {str(e)}")

    results.append(result)
    return result


def generate_report(results):
    """Generate a summary report"""
    print("\n" + "="*80)
    print("DATAMANCY STACK UI SERVICES TEST REPORT")
    print("="*80)
    print(f"Test Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Total Services Tested: {len(results)}")
    print("="*80)

    accessible_count = sum(1 for r in results if r["accessible"])
    login_attempted_count = sum(1 for r in results if r["login_attempted"])
    login_successful_count = sum(1 for r in results if r["login_successful"])

    print(f"\nSummary:")
    print(f"  - Accessible services: {accessible_count}/{len(results)}")
    print(f"  - Login attempts: {login_attempted_count}")
    print(f"  - Successful logins: {login_successful_count}")

    print("\n" + "-"*80)
    print("Detailed Results:")
    print("-"*80)

    for result in results:
        status_icon = "✓" if result["accessible"] else "✗"
        login_status = ""
        if result["login_attempted"]:
            login_status = " [LOGIN: ✓]" if result["login_successful"] else " [LOGIN: ✗]"

        print(f"\n{status_icon} {result['service']}")
        print(f"  URL: {result['url']}")
        print(f"  Accessible: {result['accessible']}{login_status}")
        if result["error"]:
            print(f"  Error: {result['error']}")
        print(f"  Screenshots: {len(result['screenshots'])}")
        for screenshot in result["screenshots"]:
            print(f"    - {screenshot}")

    # Save JSON report
    report_path = os.path.join(SCREENSHOT_DIR, "test_report.json")
    with open(report_path, "w") as f:
        json.dump({
            "timestamp": datetime.now().isoformat(),
            "summary": {
                "total_services": len(results),
                "accessible": accessible_count,
                "login_attempts": login_attempted_count,
                "login_successful": login_successful_count
            },
            "results": results
        }, f, indent=2)

    print(f"\n{'='*80}")
    print(f"JSON report saved: {report_path}")
    print("="*80)


def main():
    """Main execution"""
    print("Starting Datamancy UI Services Test")
    print(f"Screenshot directory: {SCREENSHOT_DIR}")

    # Ensure screenshot directory exists
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)

    results = []

    with sync_playwright() as p:
        print("\nLaunching browser...")
        browser = p.chromium.launch(headless=True)

        for service in SERVICES:
            test_service(browser, service, results)
            time.sleep(1)  # Brief pause between tests

        browser.close()

    generate_report(results)

    print("\n✓ All tests completed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
