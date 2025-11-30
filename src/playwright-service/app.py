#!/usr/bin/env python3
"""
Minimal Playwright service mimicking browserless API
Supports /screenshot and /function endpoints with Firefox
"""
import base64
import json
from flask import Flask, request, jsonify
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

app = Flask(__name__)

# Keep a persistent browser context
playwright = None
browser = None

def get_browser():
    global playwright, browser
    if playwright is None:
        playwright = sync_playwright().start()
    if browser is None or not browser.is_connected():
        browser = playwright.firefox.launch(
            headless=True,
            args=['--no-sandbox', '--disable-dev-shm-usage']
        )
    return browser

@app.route('/healthz', methods=['GET'])
def health():
    return jsonify({'status': 'ok'})

@app.route('/screenshot', methods=['GET'])
def screenshot():
    """GET /screenshot?url=https://example.com"""
    url = request.args.get('url')
    if not url:
        return jsonify({'error': 'url parameter required'}), 400

    try:
        browser = get_browser()
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()

        page.goto(url, wait_until='networkidle', timeout=15000)
        screenshot_bytes = page.screenshot()

        page.close()
        context.close()

        return screenshot_bytes, 200, {'Content-Type': 'image/png'}

    except PlaywrightTimeout:
        return jsonify({'error': 'Navigation timeout'}), 504
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/function', methods=['POST'])
def function():
    """
    POST /function
    Body: JavaScript code (text) or JSON {"code": "..."}

    Simplified: we translate common patterns to Python
    """
    content_type = request.content_type or ''

    # Handle both JS code directly and JSON with code field
    if 'javascript' in content_type.lower() or 'text' in content_type.lower():
        code = request.get_data(as_text=True)
    else:
        data = request.get_json() or {}
        code = data.get('code', '')

    try:
        browser = get_browser()
        context = browser.new_context(ignore_https_errors=True)

        # Simple pattern matching for common operations
        # For screenshot: extract URL and take screenshot
        if 'page.screenshot' in code and 'goto' in code:
            # Extract URL (basic pattern)
            import re
            url_match = re.search(r"goto\(['\"]([^'\"]+)['\"]", code)
            if url_match:
                url = url_match.group(1)
                page = context.new_page()
                page.goto(url, wait_until='networkidle', timeout=15000)
                screenshot_bytes = page.screenshot()
                screenshot_b64 = base64.b64encode(screenshot_bytes).decode('utf-8')
                page.close()
                context.close()
                return jsonify({'imageBase64': screenshot_b64})

        # For DOM: extract URL and get HTML
        if 'outerHTML' in code or 'documentElement' in code:
            import re
            url_match = re.search(r"goto\(['\"]([^'\"]+)['\"]", code)
            if url_match:
                url = url_match.group(1)
                page = context.new_page()
                page.goto(url, wait_until='networkidle', timeout=15000)
                html = page.content()
                page.close()
                context.close()
                return jsonify({'html': html})

        context.close()
        return jsonify({'error': 'Unsupported function pattern'}), 400

    except PlaywrightTimeout:
        return jsonify({'error': 'Navigation timeout'}), 504
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=3000)
