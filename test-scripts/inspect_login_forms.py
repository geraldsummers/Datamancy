#!/usr/bin/env python3
import os, sys, time, json
from playwright.sync_api import sync_playwright

DOMAIN = 'project-saturn.com'
USER = os.getenv('ADMIN_USER')
PASS = os.getenv('ADMIN_PASS')

# Non-Authelia services with their own authentication
SERVICES = [
    {'name': 'grafana', 'desc': 'Monitoring Dashboard'},
    {'name': 'jupyterhub', 'desc': 'Jupyter Notebooks'},
    {'name': 'vaultwarden', 'desc': 'Password Manager'},
    {'name': 'planka', 'desc': 'Kanban Board'},
    {'name': 'mastodon', 'desc': 'Social Network'},
    {'name': 'auth', 'desc': 'Authelia SSO Portal'},
]

def analyze_login_form(svc, browser):
    url = f'https://{svc["name"]}.{DOMAIN}'
    name = svc['name']
    
    print(f'\n{"="*75}')
    print(f'{name.upper()}.{DOMAIN} - {svc["desc"]}')
    print(f'{"="*75}')
    
    page = browser.new_page(ignore_https_errors=True)
    
    try:
        print(f'[1/8] Navigating to {url}...')
        page.goto(url, timeout=15000)
        page.wait_for_load_state('domcontentloaded', timeout=10000)
        time.sleep(3)  # Wait for JS to render
        
        print(f'[2/8] Current URL: {page.url}')
        
        title = page.title()
        print(f'[3/8] Page title: "{title}"')
        
        # Save HTML
        html = page.content()
        filename = f'/tmp/{name}_detailed.html'
        with open(filename, 'w') as f:
            f.write(html)
        print(f'[4/8] HTML saved: {filename} ({len(html):,} bytes)')
        
        # Analyze all input fields
        print(f'[5/8] Analyzing input fields...')
        inputs = page.locator('input').all()
        print(f'      Found {len(inputs)} input fields:')
        
        input_data = []
        for i, inp in enumerate(inputs):
            try:
                inp_id = inp.get_attribute('id') or ''
                inp_name = inp.get_attribute('name') or ''
                inp_type = inp.get_attribute('type') or ''
                inp_placeholder = inp.get_attribute('placeholder') or ''
                inp_autocomplete = inp.get_attribute('autocomplete') or ''
                
                input_data.append({
                    'id': inp_id,
                    'name': inp_name,
                    'type': inp_type,
                    'placeholder': inp_placeholder,
                    'autocomplete': inp_autocomplete
                })
                
                if i < 10:  # Show first 10
                    print(f'      [{i:2d}] type={inp_type:12s} name="{inp_name:20s}" id="{inp_id:20s}"')
            except:
                pass
        
        # Analyze buttons
        print(f'[6/8] Analyzing buttons...')
        buttons = page.locator('button').all()
        print(f'      Found {len(buttons)} buttons:')
        
        button_data = []
        for i, btn in enumerate(buttons):
            try:
                btn_text = btn.text_content()[:40] if btn.text_content() else ''
                btn_type = btn.get_attribute('type') or ''
                btn_id = btn.get_attribute('id') or ''
                btn_class = btn.get_attribute('class') or ''
                
                button_data.append({
                    'text': btn_text,
                    'type': btn_type,
                    'id': btn_id,
                    'class': btn_class[:50]
                })
                
                if i < 5:  # Show first 5
                    print(f'      [{i:2d}] "{btn_text:30s}" type={btn_type:10s} id="{btn_id:15s}"')
            except:
                pass
        
        # Detect login form
        print(f'[7/8] Login form detection:')
        
        # Look for common login field patterns
        username_selectors = [
            'input[name="username"]',
            'input[name="email"]',
            'input[name="user"]',
            'input[name="emailOrUsername"]',
            'input[type="email"]',
            'input[autocomplete="username"]',
            'input#username',
            'input#email',
        ]
        
        password_selectors = [
            'input[type="password"]',
            'input[name="password"]',
            'input#password',
        ]
        
        username_field = None
        password_field = None
        
        for selector in username_selectors:
            if page.locator(selector).count() > 0:
                username_field = selector
                break
        
        for selector in password_selectors:
            if page.locator(selector).count() > 0:
                password_field = selector
                break
        
        if username_field and password_field:
            print(f'      ✅ LOGIN FORM DETECTED')
            print(f'      Username: {username_field}')
            print(f'      Password: {password_field}')
            has_login = True
        else:
            print(f'      ⚠️  NO STANDARD LOGIN FORM')
            print(f'      (May be JS-rendered SPA or public page)')
            has_login = False
        
        # Look for SSO buttons
        print(f'[8/8] SSO detection:')
        sso_keywords = ['sso', 'oauth', 'authelia', 'sign in with']
        sso_found = []
        
        for btn_info in button_data:
            btn_text_lower = btn_info['text'].lower()
            for keyword in sso_keywords:
                if keyword in btn_text_lower:
                    sso_found.append(btn_info['text'])
                    break
        
        if sso_found:
            print(f'      ✅ SSO OPTIONS FOUND:')
            for sso in sso_found[:3]:
                print(f'         - "{sso}"')
        else:
            print(f'      No SSO options detected')
        
        # Body text preview
        body = page.text_content('body')
        print(f'\n      Body text preview (first 200 chars):')
        print(f'      {body[:200]}...')
        
        # Save analysis
        analysis = {
            'service': name,
            'url': url,
            'title': title,
            'has_login': has_login,
            'username_field': username_field,
            'password_field': password_field,
            'sso_options': sso_found,
            'input_count': len(inputs),
            'button_count': len(buttons),
            'inputs': input_data,
            'buttons': button_data
        }
        
        analysis_file = f'/tmp/{name}_analysis.json'
        with open(analysis_file, 'w') as f:
            json.dump(analysis, f, indent=2)
        print(f'\n      Analysis saved: {analysis_file}')
        
        return analysis
        
    except Exception as e:
        print(f'❌ ERROR: {str(e)}')
        return {'service': name, 'error': str(e), 'has_login': False}
    finally:
        page.close()

print('='*75)
print('DETAILED NON-AUTHELIA SERVICE LOGIN FORM INSPECTION')
print('='*75)
print(f'Domain: {DOMAIN}')
print(f'Services: {len(SERVICES)}')
print(f'Goal: Identify login forms and field selectors')
print('='*75)

results = {}
with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    
    for svc in SERVICES:
        result = analyze_login_form(svc, browser)
        results[svc['name']] = result
        time.sleep(1)
    
    browser.close()

print(f'\n\n{"="*75}')
print('FINAL SUMMARY - LOGIN FORM DETECTION')
print(f'{"="*75}')

for name, result in results.items():
    if 'error' in result:
        print(f'❌ {name:20s} - ERROR')
    elif result.get('has_login'):
        print(f'✅ {name:20s} - Login form detected')
        print(f'   Username: {result.get("username_field", "N/A")}')
        print(f'   Password: {result.get("password_field", "N/A")}')
        if result.get('sso_options'):
            print(f'   SSO: {result["sso_options"][0]}')
    else:
        print(f'⚠️  {name:20s} - No standard login form (SPA or public)')

print(f'\n{"="*75}')
print('FILES SAVED IN PLAYWRIGHT CONTAINER:')
print('  HTML files: /tmp/*_detailed.html')
print('  Analysis JSON: /tmp/*_analysis.json')
print('='*75)
