#!/usr/bin/env python3
"""
Comprehensive UI testing with Playwright - Login automation for all services
"""
import os
import sys
import json
import time
from datetime import datetime
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout, Page

# Configuration
DOMAIN = "project-saturn.com"
ADMIN_USER = "admin"
ADMIN_PASSWORD = "dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV"
SCREENSHOT_DIR = os.path.expanduser("~/datamancyscreenshots")

# Ensure screenshot directory exists
os.makedirs(SCREENSHOT_DIR, exist_ok=True)

# UI Services to test
SERVICES = [
    {
        "name": "Authelia",
        "url": f"https://auth.{DOMAIN}",
        "login_required": True,
        "login_type": "direct",  # Has its own login page
        "username_selectors": ["#username-textfield", "input[name='username']"],
        "password_selectors": ["#password-textfield", "input[name='password']"],
        "submit_selectors": ["button[type='submit']", "#sign-in-button"],
    },
    {
        "name": "Grafana",
        "url": f"https://grafana.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",  # Redirects to Authelia
        "username_selectors": ["input[name='user']", "input[name='username']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Vaultwarden",
        "url": f"https://vaultwarden.{DOMAIN}",
        "login_required": True,
        "login_type": "direct",
        "username_selectors": ["input[name='email']", "input[type='email']"],
        "password_selectors": ["input[name='masterPassword']", "input[type='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Planka",
        "url": f"https://planka.{DOMAIN}",
        "login_required": True,
        "login_type": "direct",
        "username_selectors": ["input[name='emailOrUsername']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Bookstack",
        "url": f"https://bookstack.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[name='username']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Seafile",
        "url": f"https://seafile.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[name='login']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "OnlyOffice",
        "url": f"https://onlyoffice.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[type='email']"],
        "password_selectors": ["input[type='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "JupyterHub",
        "url": f"https://jupyterhub.{DOMAIN}",
        "login_required": True,
        "login_type": "direct",
        "username_selectors": ["input[name='username']", "#username_input"],
        "password_selectors": ["input[name='password']", "#password_input"],
        "submit_selectors": ["button[type='submit']", "input[type='submit']"],
    },
    {
        "name": "Homepage",
        "url": f"https://homepage.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
    },
    {
        "name": "SOGo",
        "url": f"https://sogo.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
    },
    {
        "name": "Home Assistant",
        "url": f"https://homeassistant.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[name='username']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Kopia",
        "url": f"https://kopia.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
    },
    {
        "name": "Portainer",
        "url": f"https://portainer.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[name='username']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Mailu Admin",
        "url": f"https://mail.{DOMAIN}/admin",
        "login_required": True,
        "login_type": "direct",
        "username_selectors": ["input[name='email']"],
        "password_selectors": ["input[name='pw']", "input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Roundcube",
        "url": f"https://mail.{DOMAIN}/webmail",
        "login_required": True,
        "login_type": "direct",
        "username_selectors": ["input[name='_user']", "#rcmloginuser"],
        "password_selectors": ["input[name='_pass']", "#rcmloginpwd"],
        "submit_selectors": ["button[type='submit']", "#rcmloginsubmit"],
    },
    {
        "name": "LDAP Account Manager",
        "url": f"https://lam.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
    },
    {
        "name": "Dockge",
        "url": f"https://dockge.{DOMAIN}",
        "login_required": True,
        "login_type": "authelia",
        "username_selectors": ["input[name='username']"],
        "password_selectors": ["input[name='password']"],
        "submit_selectors": ["button[type='submit']"],
    },
    {
        "name": "Mastodon",
        "url": f"https://mastodon.{DOMAIN}",
        "login_required": False,
        "login_type": "optional",
    },
]


def save_screenshot(page: Page, service_name: str, step: str, results: dict):
    """Save screenshot and add to results"""
    safe_name = service_name.replace(" ", "_").replace("/", "_")
    filename = f"{safe_name}_{step}.png"
    filepath = os.path.join(SCREENSHOT_DIR, filename)

    try:
        page.screenshot(path=filepath, full_page=True)
        print(f"  📸 Screenshot: {filename}")
        results["screenshots"].append({"step": step, "path": filepath, "filename": filename})
        return filepath
    except Exception as e:
        print(f"  ⚠️  Screenshot failed: {e}")
        return None


def find_element(page: Page, selectors: list, timeout: int = 5000):
    """Try multiple selectors to find an element"""
    for selector in selectors:
        try:
            element = page.wait_for_selector(selector, timeout=timeout)
            if element:
                return element, selector
        except:
            continue
    return None, None


def handle_authelia_login(page: Page, service_name: str, results: dict):
    """Handle Authelia SSO login"""
    print(f"  🔐 Detected Authelia login redirect")

    # Wait for Authelia login page
    try:
        page.wait_for_url("**/auth.project-saturn.com/**", timeout=10000)
        save_screenshot(page, service_name, "authelia_login", results)

        # Find username field
        username_input, username_sel = find_element(page, ["#username-textfield", "input[name='username']"])
        if not username_input:
            results["error"] = "Authelia username field not found"
            return False

        # Find password field
        password_input, password_sel = find_element(page, ["#password-textfield", "input[name='password']"])
        if not password_input:
            results["error"] = "Authelia password field not found"
            return False

        print(f"  ✏️  Filling Authelia credentials")
        username_input.fill(ADMIN_USER)
        page.wait_for_timeout(500)
        password_input.fill(ADMIN_PASSWORD)
        page.wait_for_timeout(500)

        save_screenshot(page, service_name, "authelia_filled", results)

        # Find and click sign in button
        submit_button, submit_sel = find_element(page, ["#sign-in-button", "button[type='submit']"])
        if submit_button:
            print(f"  🖱️  Clicking Authelia sign in")
            submit_button.click()

            # Wait for redirect back to service
            page.wait_for_timeout(3000)
            save_screenshot(page, service_name, "after_authelia", results)

            # Check if we're back at the service
            if "auth.project-saturn.com" not in page.url:
                print(f"  ✅ Redirected back to service")
                return True
            else:
                results["error"] = "Still on Authelia after login attempt"
                return False
        else:
            results["error"] = "Authelia submit button not found"
            return False

    except Exception as e:
        results["error"] = f"Authelia login error: {str(e)}"
        return False


def test_service(browser, service, results_list):
    """Test a single service with login automation"""
    service_name = service["name"]
    url = service["url"]

    print(f"\n{'='*80}")
    print(f"Testing: {service_name}")
    print(f"URL: {url}")
    print('='*80)

    result = {
        "service": service_name,
        "url": url,
        "timestamp": datetime.now().isoformat(),
        "accessible": False,
        "login_attempted": False,
        "login_successful": False,
        "login_type": service.get("login_type", "unknown"),
        "error": None,
        "screenshots": [],
        "final_url": None
    }

    context = None
    try:
        # Create browser context
        context = browser.new_context(
            ignore_https_errors=True,
            viewport={'width': 1920, 'height': 1080}
        )
        page = context.new_page()

        # Navigate to service
        print(f"  🌐 Navigating to {url}")
        try:
            response = page.goto(url, timeout=30000, wait_until="domcontentloaded")
            result["accessible"] = True if response and response.status < 400 else False
            print(f"  📊 HTTP Status: {response.status if response else 'N/A'}")
        except PlaywrightTimeout:
            result["error"] = "Navigation timeout"
            print(f"  ❌ Navigation timeout")
            if context:
                context.close()
            results_list.append(result)
            return result
        except Exception as e:
            result["error"] = f"Navigation error: {str(e)}"
            print(f"  ❌ Navigation error: {str(e)}")
            if context:
                context.close()
            results_list.append(result)
            return result

        # Wait for page to load
        page.wait_for_timeout(2000)

        # Take initial screenshot
        save_screenshot(page, service_name, "01_initial", result)

        # Check if we need to login
        if not service.get("login_required", False):
            print(f"  ℹ️  No login required")
            result["login_successful"] = True
            result["final_url"] = page.url
            save_screenshot(page, service_name, "02_homepage", result)
            if context:
                context.close()
            results_list.append(result)
            return result

        result["login_attempted"] = True

        # Check if redirected to Authelia
        current_url = page.url
        if "auth.project-saturn.com" in current_url and service.get("login_type") == "authelia":
            success = handle_authelia_login(page, service_name, result)
            if success:
                result["login_successful"] = True
                result["final_url"] = page.url
                save_screenshot(page, service_name, "03_authenticated", result)
            if context:
                context.close()
            results_list.append(result)
            return result

        # Direct login (not Authelia)
        print(f"  🔑 Attempting direct login")

        # Find login form elements
        username_input, username_sel = find_element(page, service.get("username_selectors", []))
        if not username_input:
            result["error"] = "Username field not found"
            print(f"  ❌ Username field not found")
            save_screenshot(page, service_name, "02_login_page", result)
            if context:
                context.close()
            results_list.append(result)
            return result

        password_input, password_sel = find_element(page, service.get("password_selectors", []))
        if not password_input:
            result["error"] = "Password field not found"
            print(f"  ❌ Password field not found")
            if context:
                context.close()
            results_list.append(result)
            return result

        print(f"  ✏️  Filling credentials")
        print(f"     Username selector: {username_sel}")
        print(f"     Password selector: {password_sel}")

        username_input.fill(ADMIN_USER)
        page.wait_for_timeout(500)
        password_input.fill(ADMIN_PASSWORD)
        page.wait_for_timeout(500)

        save_screenshot(page, service_name, "02_credentials_filled", result)

        # Find and click submit
        submit_button, submit_sel = find_element(page, service.get("submit_selectors", []))
        if submit_button:
            print(f"  🖱️  Clicking submit button: {submit_sel}")
            submit_button.click()

            # Wait for response
            page.wait_for_timeout(5000)
            save_screenshot(page, service_name, "03_after_submit", result)

            # Check if login was successful
            final_url = page.url
            result["final_url"] = final_url

            # Heuristics to determine success
            if final_url != url and "login" not in final_url.lower():
                result["login_successful"] = True
                print(f"  ✅ Login successful (URL changed to: {final_url})")
            elif "login" in final_url.lower() or "sign-in" in final_url.lower():
                result["login_successful"] = False
                result["error"] = "Still on login page after submit"
                print(f"  ⚠️  Still on login page")
            else:
                # Take a final screenshot to verify
                save_screenshot(page, service_name, "04_final_state", result)
                # Assume success if no obvious error
                result["login_successful"] = True
                print(f"  ✅ Login appears successful")
        else:
            result["error"] = "Submit button not found"
            print(f"  ❌ Submit button not found")

        if context:
            context.close()

    except Exception as e:
        result["error"] = f"Unexpected error: {str(e)}"
        print(f"  ❌ Unexpected error: {str(e)}")
        if context:
            try:
                context.close()
            except:
                pass

    results_list.append(result)
    return result


def generate_report(results):
    """Generate comprehensive report"""
    print("\n" + "="*80)
    print("DATAMANCY UI SERVICES - COMPREHENSIVE TEST REPORT")
    print("="*80)
    print(f"Test Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Total Services: {len(results)}")
    print("="*80)

    accessible = sum(1 for r in results if r["accessible"])
    login_attempted = sum(1 for r in results if r["login_attempted"])
    login_successful = sum(1 for r in results if r["login_successful"])
    total_screenshots = sum(len(r["screenshots"]) for r in results)

    print(f"\n📊 Summary:")
    print(f"  • Accessible: {accessible}/{len(results)}")
    print(f"  • Login attempts: {login_attempted}")
    print(f"  • Successful logins: {login_successful}")
    print(f"  • Total screenshots: {total_screenshots}")

    print("\n" + "-"*80)
    print("Detailed Results:")
    print("-"*80)

    for result in results:
        icon = "✅" if result["login_successful"] else "⚠️" if result["accessible"] else "❌"
        print(f"\n{icon} {result['service']}")
        print(f"   URL: {result['url']}")
        print(f"   Accessible: {result['accessible']}")
        print(f"   Login Type: {result.get('login_type', 'N/A')}")
        print(f"   Login Attempted: {result['login_attempted']}")
        print(f"   Login Successful: {result['login_successful']}")
        if result.get("final_url"):
            print(f"   Final URL: {result['final_url']}")
        if result.get("error"):
            print(f"   ⚠️  Error: {result['error']}")
        print(f"   Screenshots ({len(result['screenshots'])}):")
        for ss in result["screenshots"]:
            print(f"      • {ss['step']}: {ss['filename']}")

    # Save JSON report
    report_path = os.path.join(SCREENSHOT_DIR, "ui_test_report.json")
    with open(report_path, "w") as f:
        json.dump({
            "timestamp": datetime.now().isoformat(),
            "summary": {
                "total_services": len(results),
                "accessible": accessible,
                "login_attempts": login_attempted,
                "login_successful": login_successful,
                "total_screenshots": total_screenshots
            },
            "results": results
        }, f, indent=2)

    print(f"\n{'='*80}")
    print(f"📄 JSON report: {report_path}")
    print(f"📁 Screenshots: {SCREENSHOT_DIR}")
    print("="*80)


def main():
    """Main execution"""
    print("🚀 Starting Datamancy UI Services Comprehensive Test")
    print(f"📂 Screenshot directory: {SCREENSHOT_DIR}\n")

    results = []

    with sync_playwright() as p:
        print("🌐 Launching Firefox browser...")
        browser = p.firefox.launch(headless=False)  # Set to True for headless

        for service in SERVICES:
            test_service(browser, service, results)
            time.sleep(2)  # Brief pause between tests

        browser.close()

    generate_report(results)

    print("\n✅ All tests completed!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
