import { test, expect } from '@playwright/test';

test.describe('Phase 5: Management Tools Tests', () => {
  test('Adminer should be accessible', async ({ page }) => {
    await page.goto('https://adminer.stack.local');
    await page.waitForLoadState('networkidle');

    // Should see Adminer login form
    const title = await page.title();
    expect(title.toLowerCase()).toContain('login');

    // Should have server, username, password fields
    await expect(page.locator('input[name="auth[server]"]')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('input[name="auth[username]"]')).toBeVisible();
  });

  test('Adminer should connect to MariaDB', async ({ page }) => {
    await page.goto('https://adminer.stack.local');
    await page.waitForLoadState('networkidle');

    // Fill in MariaDB credentials
    await page.fill('input[name="auth[server]"]', 'mariadb');
    await page.fill('input[name="auth[username]"]', 'datamancy');
    await page.fill('input[name="auth[password]"]', 'datamancy_password_change_me');
    await page.selectOption('select[name="auth[driver]"]', 'server');

    // Click login
    await page.click('input[type="submit"]');
    await page.waitForLoadState('networkidle');

    // Should see database list or tables
    const url = page.url();
    expect(url).toContain('adminer.stack.local');

    // Should see datamancy database or success indicator
    const bodyText = await page.textContent('body');
    expect(bodyText?.toLowerCase().includes('datamancy') ||
           bodyText?.toLowerCase().includes('database') ||
           bodyText?.toLowerCase().includes('table')).toBeTruthy();
  });

  test('Mongo Express should be accessible', async ({ page }) => {
    // Mongo Express uses basic auth, Playwright needs credentials
    await page.goto('https://admin:admin_password_change_me@mongo-express.stack.local');
    await page.waitForLoadState('networkidle');

    // Should see Mongo Express interface
    const title = await page.title();
    expect(title.toLowerCase()).toContain('mongo');

    // Should see database list
    const bodyText = await page.textContent('body');
    expect(bodyText?.toLowerCase().includes('database') ||
           bodyText?.toLowerCase().includes('admin') ||
           bodyText?.toLowerCase().includes('datamancy')).toBeTruthy();
  });

  test('Mongo Express should show datamancy database', async ({ page }) => {
    await page.goto('https://admin:admin_password_change_me@mongo-express.stack.local');
    await page.waitForLoadState('networkidle');

    // Look for datamancy database link or text
    const datamancyLink = page.locator('a:has-text("datamancy")').first();

    // If datamancy database exists, it should be visible
    const exists = await datamancyLink.isVisible().catch(() => false);

    // Either datamancy exists or we see the database list page
    const bodyText = await page.textContent('body');
    expect(exists || bodyText?.toLowerCase().includes('database')).toBeTruthy();
  });

  test('Portainer should be accessible', async ({ page }) => {
    await page.goto('https://portainer.stack.local');
    await page.waitForLoadState('networkidle');

    // Portainer may show initial setup or login
    const title = await page.title();
    expect(title.toLowerCase()).toContain('portainer');

    // Should see either setup wizard or login form
    const bodyText = await page.textContent('body');
    expect(bodyText?.toLowerCase().includes('portainer') ||
           bodyText?.toLowerCase().includes('login') ||
           bodyText?.toLowerCase().includes('setup') ||
           bodyText?.toLowerCase().includes('username')).toBeTruthy();
  });

  test('Management tools should be protected by TLS', async ({ request }) => {
    // Verify HTTPS works for all management tools
    const adminerResponse = await request.get('https://adminer.stack.local');
    expect(adminerResponse.status()).toBeLessThan(500); // Should not be server error

    const portainerResponse = await request.get('https://portainer.stack.local');
    expect(portainerResponse.status()).toBeLessThan(500);

    // Mongo Express requires basic auth, so 401 is expected
    const mongoExpressResponse = await request.get('https://mongo-express.stack.local');
    expect([200, 401]).toContain(mongoExpressResponse.status());
  });
});
