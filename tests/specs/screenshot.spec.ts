import { test, expect } from '@playwright/test';

test('prove browser emulation works with example.com', async ({ page }) => {
  console.log('Navigating to example.com...');
  await page.goto('https://example.com');

  console.log('Waiting for page to load...');
  await page.waitForLoadState('networkidle');

  console.log('Taking screenshot...');
  await page.screenshot({
    path: 'test-results/example-com-proof.png',
    fullPage: true
  });

  // Verify we actually loaded example.com
  const title = await page.title();
  console.log(`Page title: ${title}`);
  expect(title).toContain('Example');

  // Verify the main heading exists
  const heading = await page.locator('h1').first();
  await expect(heading).toBeVisible();
  const headingText = await heading.textContent();
  console.log(`Main heading: ${headingText}`);

  console.log('✓ Browser emulation verified - screenshot saved to test-results/example-com-proof.png');
});
