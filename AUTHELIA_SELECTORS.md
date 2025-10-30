# Authelia Login Page - Playwright Selectors Reference

**Last Updated**: 2025-10-29  
**Authelia Version**: v4.39.13  
**UI Framework**: Material-UI (MUI)

## Key Findings

1. **NO `name` attributes** on form fields - Authelia uses MUI which doesn't set `name`
2. **Password field ambiguity** - `getByLabel("Password")` matches BOTH the input AND the visibility toggle button
3. **Dynamic IDs** - Field IDs like `username-textfield` may change with MUI versions
4. **Accessible selectors work best** - Use `getByLabel()` and `getByRole()`

---

## ✅ CORRECT SELECTORS

### Username Field
```javascript
// Option 1: By accessible label (RECOMMENDED)
await page.getByLabel("Username").fill("admin");

// Option 2: By role
await page.getByRole("textbox", { name: "Username" }).fill("admin");
```

### Password Field
```javascript
// MUST use role selector to avoid ambiguity
await page.getByRole("textbox", { name: "Password" }).fill("password");

// ⚠️ DO NOT USE: getByLabel("Password") - matches 2 elements!
```

### Sign In Button
```javascript
await page.getByRole("button", { name: "Sign in" }).click();
```

### Remember Me Checkbox
```javascript
await page.getByRole("checkbox", { name: "Remember me" }).check();
```

### Reset Password Link
```javascript
await page.getByRole("button", { name: "Reset password?" }).click();
```

---

## ❌ ANTI-PATTERNS (Will Fail)

```javascript
// ❌ NO name attributes exist
await page.locator('input[name="username"]');
await page.locator('input[name="password"]');

// ❌ Ambiguous - matches input + toggle button
await page.getByLabel("Password");

// ❌ IDs may change with MUI updates
await page.locator('#username-textfield');
await page.locator('#password-textfield');

// ❌ Too fragile, depends on exact class names
await page.locator('.MuiInputBase-input');
```

---

## Full Login Flow Example

```javascript
const { test, expect } = require('@playwright/test');

test('Authelia SSO login', async ({ page }) => {
  // Navigate to protected service (redirects to Authelia)
  await page.goto('https://localai.stack.local');
  
  // Wait for Authelia login page
  await page.waitForURL(/auth\.stack\.local/);
  
  // Fill credentials
  await page.getByLabel('Username').fill('admin');
  await page.getByRole('textbox', { name: 'Password' }).fill('DatamancyTest2025!');
  
  // Optional: check remember me
  await page.getByRole('checkbox', { name: 'Remember me' }).check();
  
  // Submit
  await page.getByRole('button', { name: 'Sign in' }).click();
  
  // Wait for redirect back to service
  await page.waitForURL(/localai\.stack\.local/);
  
  // Verify authenticated
  await expect(page).toHaveURL(/localai\.stack\.local/);
});
```

---

## Field Attributes Reference

### Username Field
```json
{
  "id": "username-textfield",
  "name": "",
  "type": "text",
  "autocomplete": "username",
  "aria-label": null
}
```

### Password Field
```json
{
  "id": "password-textfield",
  "name": "",
  "type": "password",
  "autocomplete": "current-password",
  "aria-label": null
}
```

---

## Why These Selectors?

1. **Accessibility-first**: Uses ARIA labels that are less likely to change
2. **Semantic**: Role-based selectors match how users interact with elements
3. **Resilient**: Not dependent on implementation details (IDs, classes)
4. **Unambiguous**: Each selector matches exactly one element
5. **Future-proof**: Robust against CSS framework updates

---

## Common Errors & Solutions

### Error: `strict mode violation: getByLabel('Password') resolved to 2 elements`
**Cause**: Password label matches both input field and visibility toggle button  
**Solution**: Use `getByRole("textbox", { name: "Password" })` instead

### Error: `element not found: input[name="username"]`
**Cause**: Authelia/MUI doesn't set `name` attributes on form inputs  
**Solution**: Use `getByLabel("Username")` or `getByRole("textbox", { name: "Username" })`

### Error: `Timeout waiting for locator('#username-textfield')`
**Cause**: MUI may generate different IDs across versions or instances  
**Solution**: Use accessible selectors, not IDs

---

## Testing Checklist

- [ ] Use `getByLabel()` or `getByRole()` for all form fields
- [ ] Password uses `getByRole("textbox", { name: "Password" })`
- [ ] Wait for URL redirect: `await page.waitForURL(/auth\.stack\.local/)`
- [ ] Verify post-auth redirect: `await page.waitForURL(/service\.stack\.local/)`
- [ ] Set `ignoreHTTPSErrors: true` in browser context
- [ ] Use appropriate timeouts (Authelia + LDAP can be slow)

---

**Reference Script**: `tests/get-authelia-selectors.js`  
**Evidence**: `tests/test-results/localai-sso-*/error-context.md`
