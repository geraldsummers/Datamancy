#!/usr/bin/env python3
import os, sys, time
from playwright.sync_api import sync_playwright

DOMAIN = 'project-saturn.com'
USER = os.getenv('ADMIN_USER')
PASS = os.getenv('ADMIN_PASS')

SERVICES = [
    {'name': 'lam', 'title': 'LDAP', 'body': 'LDAP Account Manager'},
    {'name': 'bookstack', 'title': 'BookStack', 'body': 'BookStack'},
    {'name': 'homepage', 'title': 'Home', 'body': 'Services'},
    {'name': 'portainer', 'title': 'Portainer', 'body': 'Portainer'},
    {'name': 'dockge', 'title': 'Dockge', 'body': 'Dockge'},
    {'name': 'open-webui', 'title': 'Open WebUI', 'body': 'Open WebUI'},
    {'name': 'seafile', 'title': 'Seafile', 'body': 'Seafile'},
    {'name': 'litellm', 'title': 'Proxy', 'body': 'LiteLLM'},
]

def test_svc(svc, browser):
    url = f'https://{svc["name"]}.{DOMAIN}'
    print(f'\nTesting: {svc["name"]}.{DOMAIN}')
    
    page = browser.new_page(ignore_https_errors=True)
    
    try:
        page.goto(url, timeout=15000)
        page.wait_for_load_state('networkidle', timeout=10000)
        time.sleep(2)
        
        if 'auth.project-saturn.com' in page.url:
            page.fill('input#username-textfield', USER)
            time.sleep(0.5)
            page.fill('input#password-textfield', PASS)
            time.sleep(0.5)
            page.click('button#sign-in-button')
            time.sleep(8)
        
        title = page.title()
        body = page.text_content('body')
        
        # Check if we successfully logged in
        found_title = svc['title'].lower() in title.lower()
        found_body = svc['body'].lower() in body.lower()
        
        if found_title or found_body:
            print(f'  ✅ PASS - {title[:40]}')
            return True
        elif 'auth.project-saturn.com' not in page.url:
            print(f'  ✅ PASS - Redirected to service')
            return True
        else:
            print(f'  ❌ FAIL - {title[:40]}')
            return False
            
    except Exception as e:
        print(f'  ❌ ERROR: {str(e)[:50]}')
        return False
    finally:
        page.close()

print('='*70)
print('COMPLETE AUTHELIA LOGIN VERIFICATION TEST')
print('='*70)
print(f'Domain: {DOMAIN}')
print(f'User: {USER}')
print(f'Testing {len(SERVICES)} services')
print('='*70)

results = {}
with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    for svc in SERVICES:
        results[svc['name']] = test_svc(svc, browser)
        time.sleep(1)
    browser.close()

print(f'\n{'='*70}')
print('FINAL RESULTS')
print('='*70)

success = sum(1 for v in results.values() if v)
for name, result in results.items():
    status = '✅ PASS' if result else '❌ FAIL'
    print(f'{status} - {name}.{DOMAIN}')

print(f'\n{success}/{len(results)} services logged in successfully ({success*100//len(results)}%)')
print('='*70)
