// Firefox Profile Fixture - Uses persistent profile with CA cert
import { test as base, firefox } from '@playwright/test';

export const test = base.extend({
  context: async ({}, use) => {
    const context = await firefox.launchPersistentContext('/firefox-profile', {
      acceptDownloads: true,
      ignoreHTTPSErrors: false,
      firefoxUserPrefs: {
        // Enable system root certificates
        'security.enterprise_roots.enabled': true,
        // Additional cert trust settings
        'security.cert_pinning.enforcement_level': 0,
      },
    });
    await use(context);
    await context.close();
  },
  page: async ({ context }, use) => {
    const page = context.pages()[0] || await context.newPage();
    await use(page);
  },
});

export { expect } from '@playwright/test';
