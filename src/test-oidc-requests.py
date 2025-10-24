#!/usr/bin/env python3
"""
Automated OIDC Login Flow Test using HTTP requests
Tests: Grafana -> Dex -> LDAP -> Grafana
"""

import requests
import re
from urllib.parse import urlparse, parse_qs
import sys

def test_oidc_flow():
    print("=" * 70)
    print("        AUTOMATED OIDC LOGIN FLOW TEST (HTTP)")
    print("=" * 70)
    print()

    session = requests.Session()
    session.verify = False  # For self-signed certs

    try:
        # Step 1: Access Grafana OAuth endpoint
        print("[1/5] Accessing Grafana OAuth endpoint...")
        response = session.get(
            'http://grafana.lab.localhost:3000/login/generic_oauth',
            allow_redirects=False
        )

        if response.status_code not in [302, 303]:
            raise Exception(f"Expected redirect, got {response.status_code}")

        oauth_url = response.headers.get('Location')
        print(f"      ✓ HTTP {response.status_code}")
        print(f"      ✓ Redirect to: {oauth_url[:80]}...")

        if 'dex' not in oauth_url:
            raise Exception(f"Not redirecting to Dex! URL: {oauth_url}")

        # Step 2: Follow redirect to Dex
        print("\n[2/5] Following redirect to Dex...")
        response = session.get(oauth_url, allow_redirects=True)

        if response.status_code != 200:
            raise Exception(f"Dex page returned {response.status_code}")

        print(f"      ✓ Dex login page loaded")
        print(f"      ✓ Final URL: {response.url}")

        # Extract state from URL
        parsed = urlparse(response.url)
        state = parse_qs(parsed.query).get('state', [None])[0]
        print(f"      ✓ State parameter: {state[:20]}...")

        # Step 3: Submit credentials to Dex
        print("\n[3/5] Submitting credentials to Dex...")
        login_url = f"https://dex.lab.localhost/auth/ldap/login?state={state}"

        response = session.post(
            login_url,
            data={
                'login': 'authtest',
                'password': 'TestAuth123!'
            },
            allow_redirects=False
        )

        if response.status_code not in [302, 303]:
            raise Exception(f"Login failed with HTTP {response.status_code}")

        print(f"      ✓ HTTP {response.status_code} (redirect)")
        print(f"      ✓ Credentials accepted by Dex")

        approval_url = response.headers.get('Location')

        # Handle relative URLs
        if not approval_url.startswith('http'):
            approval_url = f"https://dex.lab.localhost{approval_url}"

        print(f"      ✓ Approval URL: {approval_url[:60]}...")

        # Step 4: Follow approval flow
        print("\n[4/5] Following approval flow...")
        response = session.get(approval_url, allow_redirects=False)

        # Check if it's an approval page (needs form submission) or redirect
        if response.status_code == 200 and 'approval' in response.url:
            print(f"      ✓ Approval page loaded, submitting form...")
            # Submit approval form with POST to same URL
            response = session.post(approval_url, data={'approval': 'approve'}, allow_redirects=True)

        # Follow any remaining redirects
        if response.status_code in [302, 303]:
            redirect_url = response.headers.get('Location')
            print(f"      ✓ Following redirect...")
            response = session.get(redirect_url, allow_redirects=True)

        final_url = response.url
        print(f"      ✓ Final URL: {final_url}")

        if 'grafana' not in final_url:
            raise Exception(f"Not redirected to Grafana! URL: {final_url}")

        print(f"      ✓ Back on Grafana")

        # Step 5: Verify logged in
        print("\n[5/5] Verifying logged in state...")
        response = session.get('http://grafana.lab.localhost:3000/')

        # Check if we're logged in by looking for login form
        has_login_form = 'type="password"' in response.text and 'Sign in' in response.text

        if has_login_form:
            raise Exception("Still on login page - authentication failed!")

        print(f"      ✓ Not on login page")
        print(f"      ✓ HTTP {response.status_code}")

        # Try to access API with session
        api_response = session.get('http://grafana.lab.localhost:3000/api/user')
        if api_response.status_code == 200:
            user_data = api_response.json()
            print(f"      ✓ API access working")
            print(f"      ✓ Logged in as: {user_data.get('login', user_data.get('email', 'unknown'))}")

        # Success!
        print()
        print("╔" + "═" * 68 + "╗")
        print("║" + " " * 20 + "✅ OIDC FLOW TEST PASSED!" + " " * 21 + "║")
        print("╚" + "═" * 68 + "╝")
        print()
        print("Verified:")
        print("  • Grafana OAuth redirect to Dex ✓")
        print("  • Dex login page accessible ✓")
        print("  • LDAP authentication successful ✓")
        print("  • OAuth approval flow completed ✓")
        print("  • Redirected back to Grafana ✓")
        print("  • Successfully logged in ✓")
        print()

        return True

    except Exception as e:
        print()
        print("╔" + "═" * 68 + "╗")
        print("║" + " " * 28 + "❌ TEST FAILED" + " " * 25 + "║")
        print("╚" + "═" * 68 + "╝")
        print()
        print(f"Error: {str(e)}")
        print()
        return False

if __name__ == "__main__":
    try:
        success = test_oidc_flow()
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"Fatal error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
