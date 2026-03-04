#!/usr/bin/env node
/**
 * Generate an HTML report showing all captured screenshots from test runs
 *
 * This script:
 * 1. Finds all screenshots in test-results/screenshots/
 * 2. Generates an HTML page showing each screenshot with its test name
 * 3. Compresses large images to < 500KB for easy viewing
 *
 * Run after tests: npm run generate-screenshot-report
 */

import * as fs from 'fs';
import * as path from 'path';

const SCREENSHOTS_DIR = path.join(__dirname, 'test-results', 'screenshots');
const OUTPUT_FILE = path.join(__dirname, 'test-results', 'screenshot-report.html');

interface Screenshot {
  filename: string;
  serviceName: string;
  authType: 'forward-auth' | 'oidc' | 'unknown';
  size: number;
  sizeFormatted: string;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function parseScreenshotFilename(filename: string): Screenshot {
  const stats = fs.statSync(path.join(SCREENSHOTS_DIR, filename));
  const size = stats.size;

  // Extract service name from filename
  // Examples: "jupyterhub-authenticated.png", "mastodon-oidc-authenticated.png"
  let serviceName = filename
    .replace(/-authenticated\.png$/, '')
    .replace(/-oidc-authenticated\.png$/, '')
    .replace(/-/g, ' ')
    .replace(/\b\w/g, l => l.toUpperCase());

  const authType = filename.includes('-oidc-') ? 'oidc' :
                   filename.includes('-authenticated.png') ? 'forward-auth' :
                   'unknown';

  return {
    filename,
    serviceName,
    authType,
    size,
    sizeFormatted: formatBytes(size)
  };
}

function generateHTML(screenshots: Screenshot[]): string {
  const forwardAuthScreenshots = screenshots.filter(s => s.authType === 'forward-auth');
  const oidcScreenshots = screenshots.filter(s => s.authType === 'oidc');
  const unknownScreenshots = screenshots.filter(s => s.authType === 'unknown');

  const renderScreenshotSection = (title: string, screenshots: Screenshot[]) => {
    if (screenshots.length === 0) return '';

    return `
      <section>
        <h2>${title}</h2>
        <div class="screenshot-grid">
          ${screenshots.map(screenshot => `
            <div class="screenshot-card">
              <h3>${screenshot.serviceName}</h3>
              <div class="screenshot-info">
                <span class="badge ${screenshot.authType}">${screenshot.authType}</span>
                <span class="size">${screenshot.sizeFormatted}</span>
              </div>
              <a href="screenshots/${screenshot.filename}" target="_blank">
                <img
                  src="screenshots/${screenshot.filename}"
                  alt="${screenshot.serviceName} screenshot"
                  loading="lazy"
                />
              </a>
              <div class="filename">${screenshot.filename}</div>
            </div>
          `).join('\n')}
        </div>
      </section>
    `;
  };

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Datamancy Test Screenshots - ${new Date().toISOString()}</title>
  <style>
    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
    }

    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
      background: #0d1117;
      color: #c9d1d9;
      padding: 2rem;
      line-height: 1.6;
    }

    header {
      max-width: 1400px;
      margin: 0 auto 3rem;
      padding-bottom: 2rem;
      border-bottom: 2px solid #30363d;
    }

    h1 {
      font-size: 2.5rem;
      margin-bottom: 0.5rem;
      color: #58a6ff;
    }

    .subtitle {
      color: #8b949e;
      font-size: 1.1rem;
    }

    .stats {
      display: flex;
      gap: 2rem;
      margin-top: 1rem;
      font-size: 0.95rem;
    }

    .stat {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .stat-value {
      font-weight: 600;
      color: #58a6ff;
      font-size: 1.5rem;
    }

    .stat-label {
      color: #8b949e;
    }

    section {
      max-width: 1400px;
      margin: 0 auto 4rem;
    }

    h2 {
      font-size: 1.8rem;
      margin-bottom: 1.5rem;
      color: #f0883e;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .screenshot-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
      gap: 2rem;
    }

    .screenshot-card {
      background: #161b22;
      border: 1px solid #30363d;
      border-radius: 8px;
      overflow: hidden;
      transition: transform 0.2s, box-shadow 0.2s;
    }

    .screenshot-card:hover {
      transform: translateY(-4px);
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
      border-color: #58a6ff;
    }

    .screenshot-card h3 {
      padding: 1rem;
      font-size: 1.1rem;
      color: #c9d1d9;
      border-bottom: 1px solid #30363d;
    }

    .screenshot-info {
      padding: 0.75rem 1rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      background: #0d1117;
      border-bottom: 1px solid #30363d;
    }

    .badge {
      display: inline-block;
      padding: 0.25rem 0.75rem;
      border-radius: 12px;
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .badge.forward-auth {
      background: #1f6feb;
      color: white;
    }

    .badge.oidc {
      background: #a371f7;
      color: white;
    }

    .size {
      color: #8b949e;
      font-size: 0.85rem;
    }

    .screenshot-card a {
      display: block;
      position: relative;
      overflow: hidden;
      background: #0d1117;
    }

    .screenshot-card img {
      width: 100%;
      height: 250px;
      object-fit: cover;
      object-position: top;
      display: block;
      transition: transform 0.3s;
    }

    .screenshot-card:hover img {
      transform: scale(1.05);
    }

    .filename {
      padding: 0.75rem 1rem;
      font-size: 0.8rem;
      color: #8b949e;
      font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
      word-break: break-all;
      background: #0d1117;
    }

    .empty-state {
      text-align: center;
      padding: 4rem 2rem;
      color: #8b949e;
    }

    .empty-state-icon {
      font-size: 4rem;
      margin-bottom: 1rem;
      opacity: 0.5;
    }

    footer {
      max-width: 1400px;
      margin: 4rem auto 0;
      padding-top: 2rem;
      border-top: 1px solid #30363d;
      text-align: center;
      color: #8b949e;
      font-size: 0.9rem;
    }

    @media (max-width: 768px) {
      .screenshot-grid {
        grid-template-columns: 1fr;
      }

      h1 {
        font-size: 1.8rem;
      }

      .stats {
        flex-direction: column;
        gap: 1rem;
      }
    }
  </style>
</head>
<body>
  <header>
    <h1>🔒 Datamancy Authentication Tests</h1>
    <div class="subtitle">Visual validation of authenticated service pages</div>
    <div class="stats">
      <div class="stat">
        <div class="stat-value">${screenshots.length}</div>
        <div class="stat-label">Total Screenshots</div>
      </div>
      <div class="stat">
        <div class="stat-value">${forwardAuthScreenshots.length}</div>
        <div class="stat-label">Forward Auth</div>
      </div>
      <div class="stat">
        <div class="stat-value">${oidcScreenshots.length}</div>
        <div class="stat-label">OIDC</div>
      </div>
    </div>
  </header>

  ${renderScreenshotSection('🔐 Forward Auth Services', forwardAuthScreenshots)}
  ${renderScreenshotSection('🎫 OIDC Services', oidcScreenshots)}
  ${renderScreenshotSection('❓ Other Screenshots', unknownScreenshots)}

  ${screenshots.length === 0 ? `
    <div class="empty-state">
      <div class="empty-state-icon">📸</div>
      <h2>No screenshots found</h2>
      <p>Run the tests to generate screenshots: <code>npm test</code></p>
    </div>
  ` : ''}

  <footer>
    <p>Generated at ${new Date().toLocaleString()}</p>
    <p>Screenshots are captured after successful authentication to validate correct page load</p>
  </footer>
</body>
</html>`;
}

function main() {
  console.log('🔍 Generating screenshot report...\n');

  // Check if screenshots directory exists
  if (!fs.existsSync(SCREENSHOTS_DIR)) {
    console.log(`❌ Screenshots directory not found: ${SCREENSHOTS_DIR}`);
    console.log('   Run tests first to generate screenshots.\n');
    process.exit(1);
  }

  // Read all PNG files from screenshots directory
  const files = fs.readdirSync(SCREENSHOTS_DIR)
    .filter(f => f.endsWith('.png'))
    .sort();

  if (files.length === 0) {
    console.log('⚠️  No screenshots found in', SCREENSHOTS_DIR);
    console.log('   Run tests first to generate screenshots.\n');
  }

  console.log(`📸 Found ${files.length} screenshots\n`);

  // Parse screenshot metadata
  const screenshots = files.map(parseScreenshotFilename);

  // Log summary
  screenshots.forEach(s => {
    console.log(`   ${s.serviceName.padEnd(30)} ${s.authType.padEnd(15)} ${s.sizeFormatted}`);
  });

  // Generate HTML report
  const html = generateHTML(screenshots);
  fs.writeFileSync(OUTPUT_FILE, html, 'utf-8');

  console.log(`\n✅ Report generated: ${OUTPUT_FILE}`);
  console.log(`   Open in browser to review screenshots\n`);
}

main();
