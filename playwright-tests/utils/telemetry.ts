/**
 * Telemetry utilities for debugging selector issues
 *
 * Logs extensive page structure information to help identify elements
 */

import { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Log comprehensive page telemetry
 */
export async function logPageTelemetry(page: Page, title: string = 'Page') {
  console.log(`\n   📊 ${title} Telemetry:`);
  console.log('   ' + '═'.repeat(75));

  try {
    // Basic page info
    const url = page.url();
    const pageTitle = await page.title();
    console.log(`   URL:   ${url}`);
    console.log(`   Title: "${pageTitle}"`);
    console.log('   ' + '─'.repeat(75));

    // Form inputs
    await logInputElements(page);

    // Buttons
    await logButtonElements(page);

    // Links
    await logLinkElements(page);

    // Headings
    await logHeadingElements(page);

    // ARIA landmarks
    await logAriaLandmarks(page);

  } catch (error) {
    console.warn(`   ⚠️  Telemetry extraction error: ${error}`);
  }

  console.log('   ' + '═'.repeat(75) + '\n');
}

/**
 * Log all input elements with attributes
 */
async function logInputElements(page: Page) {
  const inputs = await page.locator('input').all();

  if (inputs.length === 0) {
    console.log('   Inputs: (none)');
    return;
  }

  console.log(`   Inputs (${inputs.length}):`);

  for (let i = 0; i < Math.min(inputs.length, 20); i++) {
    const input = inputs[i];
    try {
      const type = await input.getAttribute('type') || 'text';
      const name = await input.getAttribute('name') || '';
      const id = await input.getAttribute('id') || '';
      const placeholder = await input.getAttribute('placeholder') || '';
      const ariaLabel = await input.getAttribute('aria-label') || '';
      const className = await input.getAttribute('class') || '';

      console.log(`     [${i}] type="${type}" name="${name}" id="${id}"`);
      if (placeholder) console.log(`         placeholder="${placeholder}"`);
      if (ariaLabel) console.log(`         aria-label="${ariaLabel}"`);
      if (className) console.log(`         class="${className.substring(0, 50)}${className.length > 50 ? '...' : ''}"`);
    } catch (e) {
      console.log(`     [${i}] (could not extract)`);
    }
  }

  if (inputs.length > 20) {
    console.log(`     ... and ${inputs.length - 20} more`);
  }
}

/**
 * Log all button elements
 */
async function logButtonElements(page: Page) {
  const buttons = await page.locator('button').all();

  if (buttons.length === 0) {
    console.log('   Buttons: (none)');
    return;
  }

  console.log(`   Buttons (${buttons.length}):`);

  for (let i = 0; i < Math.min(buttons.length, 15); i++) {
    const button = buttons[i];
    try {
      const type = await button.getAttribute('type') || '';
      const text = await button.textContent() || '';
      const id = await button.getAttribute('id') || '';
      const ariaLabel = await button.getAttribute('aria-label') || '';

      console.log(`     [${i}] type="${type}" text="${text.trim().substring(0, 40)}"`);
      if (id) console.log(`         id="${id}"`);
      if (ariaLabel) console.log(`         aria-label="${ariaLabel}"`);
    } catch (e) {
      console.log(`     [${i}] (could not extract)`);
    }
  }

  if (buttons.length > 15) {
    console.log(`     ... and ${buttons.length - 15} more`);
  }
}

/**
 * Log prominent links
 */
async function logLinkElements(page: Page) {
  const links = await page.locator('a').all();

  if (links.length === 0) {
    console.log('   Links: (none)');
    return;
  }

  console.log(`   Links (showing first 10 of ${links.length}):`);

  for (let i = 0; i < Math.min(links.length, 10); i++) {
    const link = links[i];
    try {
      const href = await link.getAttribute('href') || '';
      const text = await link.textContent() || '';

      if (text.trim()) {
        console.log(`     [${i}] "${text.trim().substring(0, 40)}" → ${href.substring(0, 50)}`);
      }
    } catch (e) {
      // Skip
    }
  }
}

/**
 * Log heading elements
 */
async function logHeadingElements(page: Page) {
  const headings = await page.locator('h1, h2, h3').all();

  if (headings.length === 0) {
    return;
  }

  console.log(`   Headings (${headings.length}):`);

  for (const heading of headings) {
    try {
      const tag = await heading.evaluate(el => el.tagName.toLowerCase());
      const text = await heading.textContent() || '';
      if (text.trim()) {
        console.log(`     <${tag}> ${text.trim().substring(0, 60)}`);
      }
    } catch (e) {
      // Skip
    }
  }
}

/**
 * Log ARIA landmarks for accessibility
 */
async function logAriaLandmarks(page: Page) {
  const landmarks = await page.locator('[role]').all();

  if (landmarks.length === 0) {
    return;
  }

  const roleMap = new Map<string, number>();

  for (const landmark of landmarks) {
    try {
      const role = await landmark.getAttribute('role') || '';
      roleMap.set(role, (roleMap.get(role) || 0) + 1);
    } catch (e) {
      // Skip
    }
  }

  if (roleMap.size > 0) {
    console.log(`   ARIA Roles: ${Array.from(roleMap.entries()).map(([role, count]) => `${role}(${count})`).join(', ')}`);
  }
}

/**
 * Save page HTML for debugging
 */
export async function savePageHTML(page: Page, filename: string) {
  const html = await page.content();

  const dir = 'test-results/html-snapshots';
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const filepath = path.join(dir, filename);
  fs.writeFileSync(filepath, html);

  console.log(`   💾 HTML saved: ${filepath}`);
}

/**
 * Log network requests (for debugging auth redirects)
 */
export function setupNetworkLogging(page: Page, prefix: string = '') {
  page.on('request', (request) => {
    if (request.resourceType() === 'document') {
      console.log(`   → ${prefix} REQUEST: ${request.method()} ${request.url()}`);
    }
  });

  page.on('response', (response) => {
    if (response.request().resourceType() === 'document') {
      console.log(`   ← ${prefix} RESPONSE: ${response.status()} ${response.url()}`);
    }
  });
}
