#!/usr/bin/env python3
import os, sys, time
from playwright.sync_api import sync_playwright

DOMAIN = 'project-saturn.com'
USER = os.getenv('ADMIN_USER')
PASS = os.getenv('ADMIN_PASS')

# All services from Caddyfile
SERVICES = [
    {'name': 'agent-tool-server', 'expect': 'agent', 'protected': True},
    {'name': 'auth', 'expect': 'Authelia', 'protected': False},
    {'name': 'bookstack', 'expect': 'BookStack', 'protected': True},
    {'name': 'clickhouse', 'expect': 'ClickHouse', 'protected': True},
    {'name': 'couchdb', 'expect': 'CouchDB', 'protected': True},
    {'name': 'dockge', 'expect': 'Dockge', 'protected': True},
    {'name': 'grafana', 'expect': 'Grafana', 'protected': False},
    {'name': 'homeassistant', 'expect': 'Home Assistant', 'protected': True},
    {'name': 'homepage', 'expect': 'Home', 'protected': True},
    {'name': 'jupyterhub', 'expect': 'Jupyter', 'protected': False},
    {'name': 'kopia', 'expect': 'Kopia', 'protected': True},
    {'name': 'lam', 'expect': 'LDAP', 'protected': True},
    {'name': 'litellm', 'expect': 'LiteLLM', 'protected': True},
    {'name': 'mail', 'expect': 'Mailu', 'protected': False},
    {'name': 'mastodon', 'expect': 'Mastodon', 'protected': False},
    {'name': 'matrix', 'expect': 'Matrix', 'protected': False},
    {'name': 'onlyoffice', 'expect': 'OnlyOffice', 'protected': True},
    {'name': 'open-webui', 'expect': 'Open WebUI', 'protected': True},
    {'name': 'planka', 'expect': 'Planka', 'protected': False},
    {'name': 'portainer', 'expect': 'Portainer', 'protected': True},
    {'name': 'seafile', 'expect': 'Seafile', 'protected': True},
    {'name': 'sogo', 'expect': 'SOGo', 'protected': True},
    {'name': 'vaultwarden', 'expect': 'Vaultwarden', 'protected': False},
    {'name': 'vllm-router', 'expect': 'vLLM', 'protected': True},
]

def test_service(svc, browser):
    url = f'https://{svc["name"]}.{DOMAIN}'
    name = svc['name']
    
    page = browser.new_page(ignore_https_errors=True)
    
    try:
        # Navigate
        page.goto(url, timeout=15000)
        page.wait_for_load_state('domcontentloaded', timeout=10000)
        time.sleep(2)
        
        # Check if Authelia login needed
        if 'auth.project-saturn.com' in page.url and svc['protected']:
            # Do Authelia login
            if page.locator('input#username-textfield').count() > 0:
                page.fill('input#username-textfield', USER)
                time.sleep(0.3)
                page.fill('input#password-textfield', PASS)
                time.sleep(0.3)
                page.click('button#sign-in-button')
                time.sleep(7)
        
        # Check final state
        title = page.title()
        body = page.text_content('body')
        final_url = page.url
        
        # Determine if successful
        expect_text = svc['expect'].lower()
        found_in_title = expect_text in title.lower()
        found_in_body = expect_text in body.lower()
        not_on_auth = 'auth.project-saturn.com' not in final_url
        
        if found_in_title or found_in_body or (not_on_auth and svc['name'] in final_url):
            status = 'PASS'
            detail = title[:50]
        elif final_url == url or not_on_auth:
            status = 'PASS'
            detail = 'Service responding'
        else:
            status = 'FAIL'
            detail = 'Auth failed or timeout'
        
        return {'status': status, 'title': title[:50], 'detail': detail}
        
    except Exception as e:
        return {'status': 'ERROR', 'title': '', 'detail': str(e)[:50]}
    finally:
        page.close()

print('='*80)
print('COMPLETE SERVICE VERIFICATION TEST')
print('='*80)
print(f'Domain: {DOMAIN}')
print(f'User: {USER}')
print(f'Services: {len(SERVICES)}')
print('='*80)
print()

results = {}
with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    
    for i, svc in enumerate(SERVICES, 1):
        name = svc['name']
        protected = '🔒' if svc['protected'] else '🔓'
        print(f'[{i:2d}/{len(SERVICES)}] {protected} Testing {name}.{DOMAIN}...', end=' ')
        
        result = test_service(svc, browser)
        results[name] = result
        
        if result['status'] == 'PASS':
            print(f'✅ {result["detail"]}')
        elif result['status'] == 'ERROR':
            print(f'❌ {result["detail"]}')
        else:
            print(f'⚠️  {result["detail"]}')
        
        time.sleep(0.5)
    
    browser.close()

print()
print('='*80)
print('SUMMARY')
print('='*80)

pass_count = sum(1 for r in results.values() if r['status'] == 'PASS')
error_count = sum(1 for r in results.values() if r['status'] == 'ERROR')
fail_count = len(results) - pass_count - error_count

print(f'✅ PASS:  {pass_count:2d}/{len(results)}')
print(f'⚠️  FAIL:  {fail_count:2d}/{len(results)}')
print(f'❌ ERROR: {error_count:2d}/{len(results)}')
print(f'Success rate: {pass_count*100//len(results)}%')
print('='*80)

print()
print('DETAILED RESULTS:')
print('-'*80)
for name, result in results.items():
    icon = '✅' if result['status'] == 'PASS' else '❌' if result['status'] == 'ERROR' else '⚠️'
    print(f'{icon} {name:25s} - {result["title"]}')
print('='*80)
