import { test, expect } from '@playwright/test';

test('login to JupyterHub via Authelia SSO and verify access', async ({ page }) => {
  console.log('Step 1: Navigate to JupyterHub...');
  await page.goto('https://jupyterhub.stack.local', {
    waitUntil: 'networkidle',
    timeout: 30000
  });

  // Take screenshot of initial page
  await page.screenshot({
    path: 'screenshots/jupyterhub/01-jupyterhub-landing.png',
    fullPage: true
  });

  console.log('Step 2: Click "Sign in with Authelia" or login button...');

  // Look for OAuth login button or regular login button
  const loginButton = page.locator('button, a, input[type="submit"]').filter({
    hasText: /sign in|login|authelia/i
  }).first();

  await loginButton.click();

  await page.waitForLoadState('networkidle');
  console.log(`Redirected to: ${page.url()}`);

  await page.screenshot({
    path: 'screenshots/jupyterhub/02-authelia-login-page.png',
    fullPage: true
  });

  console.log('Step 3: Enter credentials (admin / ChangeMe123!)...');

  // Wait for Authelia login form
  await page.waitForSelector('input[name="username"], input#username-textfield', { timeout: 10000 });

  // Fill in username
  const usernameField = page.locator('input[name="username"], input#username-textfield').first();
  await usernameField.fill('admin');

  // Fill in password
  const passwordField = page.locator('input[name="password"], input[type="password"]').first();
  await passwordField.fill('ChangeMe123!');

  await page.screenshot({
    path: 'screenshots/jupyterhub/03-credentials-filled.png',
    fullPage: true
  });

  console.log('Step 4: Submit login form...');
  const signInButton = page.locator('button[type="submit"], button').filter({ hasText: /sign in|login/i }).first();
  await signInButton.click();

  // Wait for redirect and check for consent page
  await page.waitForTimeout(2000);
  console.log(`After login submission: ${page.url()}`);

  // Step 4.5: Handle OAuth consent page if present
  try {
    // Wait for either consent page or jupyterhub redirect
    await page.waitForURL('**/consent/**', { timeout: 5000 }).catch(() => {});

    if (page.url().includes('/consent')) {
      console.log('Step 4.5: OAuth consent page detected, clicking ACCEPT...');

      await page.screenshot({
        path: 'screenshots/jupyterhub/04-consent-page.png',
        fullPage: true
      });

      const acceptButton = page.locator('button').filter({ hasText: /accept/i }).first();
      await acceptButton.click();

      console.log('Waiting for redirect to JupyterHub after consent...');
      await page.waitForURL('**/jupyterhub.stack.local/**', { timeout: 30000 });
      console.log(`After consent: ${page.url()}`);
    }
  } catch (e) {
    console.log(`Consent handling: ${e.message}`);
  }

  await page.screenshot({
    path: 'screenshots/jupyterhub/05-after-auth.png',
    fullPage: true
  });

  console.log('Step 5: Verify we are logged into JupyterHub...');

  // Wait a bit for JupyterHub to fully load
  await page.waitForTimeout(3000);

  // Check if we're on JupyterHub (not login page)
  const currentUrl = page.url();
  console.log(`Final URL: ${currentUrl}`);

  const isOnJupyterHub = currentUrl.includes('jupyterhub.stack.local') &&
                         (currentUrl.includes('/hub/') || currentUrl.includes('/user/'));
  console.log(`On JupyterHub (authenticated): ${isOnJupyterHub}`);

  // Look for JupyterHub UI elements
  const hasJupyterHubElements = await page.locator('#jupyterhub-logo, .jupyter-nav, [id*="jupyter"]').count() > 0;
  console.log(`Has JupyterHub UI elements: ${hasJupyterHubElements}`);

  // Take final screenshot showing logged-in state
  await page.screenshot({
    path: 'screenshots/jupyterhub/06-logged-in.png',
    fullPage: true
  });

  console.log('\n=== Login Test Complete ===');
  console.log(`Success: ${isOnJupyterHub}`);
  console.log(`Final URL: ${currentUrl}`);

  expect(isOnJupyterHub || hasJupyterHubElements).toBeTruthy();

  console.log('\nStep 6: Wait for JupyterLab server to spawn...');

  // JupyterHub needs to spawn the user's Jupyter server - wait up to 60s
  try {
    await page.waitForURL('**/user/**', { timeout: 60000 });
    console.log('JupyterLab server spawned successfully');
  } catch (e) {
    console.log(`Still waiting for server spawn: ${page.url()}`);
  }

  // Wait for JupyterLab to fully load
  await page.waitForTimeout(10000);

  await page.screenshot({
    path: 'screenshots/jupyterhub/07-jupyterlab-interface.png',
    fullPage: true
  });

  console.log('Step 7: Create a new notebook...');

  // Look for "New" button or launcher
  try {
    // Try clicking the Launcher's notebook button or File > New > Notebook
    const notebookButton = page.locator('button, div[role="button"], .jp-LauncherCard').filter({
      hasText: /notebook|python/i
    }).first();

    await notebookButton.waitFor({ timeout: 20000 });
    await notebookButton.click();
    console.log('Clicked notebook creation button');

    await page.waitForTimeout(3000);

    await page.screenshot({
      path: 'screenshots/jupyterhub/08-notebook-created.png',
      fullPage: true
    });

    // Try to identify notebook interface elements
    const hasNotebookCell = await page.locator('.jp-Cell, .jp-Notebook, [role="main"]').count() > 0;
    console.log(`Notebook interface detected: ${hasNotebookCell}`);

    // Type some code in the first cell
    console.log('Step 8: Write code in notebook cell...');
    const codeCell = page.locator('.jp-Cell-inputArea, .CodeMirror, [role="textbox"]').first();
    await codeCell.click();
    await page.keyboard.type('print("Hello from Datamancy JupyterHub!")');

    await page.waitForTimeout(2000);

    await page.screenshot({
      path: 'screenshots/jupyterhub/09-code-entered.png',
      fullPage: true
    });

    // Execute the cell (Shift+Enter)
    console.log('Step 9: Execute notebook cell...');
    await page.keyboard.press('Shift+Enter');

    await page.waitForTimeout(5000);

    await page.screenshot({
      path: 'screenshots/jupyterhub/10-code-executed.png',
      fullPage: true
    });

    console.log('\n=== Notebook Creation Test Complete ===');
    console.log('Screenshots saved to: screenshots/jupyterhub/');

    expect(hasNotebookCell).toBeTruthy();
  } catch (error) {
    console.error(`Error creating notebook: ${error.message}`);
    await page.screenshot({
      path: 'screenshots/jupyterhub/error-notebook-creation.png',
      fullPage: true
    });
    throw error;
  }
});
