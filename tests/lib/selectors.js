// tests/lib/selectors.js
// Evidence-based selectors from AUTHELIA_SELECTORS.md and test_web_ui.js

/**
 * Authelia login form selectors (from AUTHELIA_SELECTORS.md)
 * These are accessibility-first and proven to work with MUI
 */
const authelia = {
  // Use getByLabel for username (works with MUI)
  username: 'Username',

  // MUST use getByRole for password to avoid ambiguity with visibility toggle
  passwordRole: { role: 'textbox', name: 'Password' },

  // Sign in button
  signInButton: [
    'button:has-text("Sign in")',
    'button[type="submit"]',
    'text=/sign in/i',
  ],

  // Consent/authorization (first-time per client)
  consentButtons: [
    'button:has-text("ACCEPT")',  // Authelia uses all-caps
    'button:has-text("Authorize")',
    'button:has-text("Allow")',
    'button:has-text("Approve")',
    'button:has-text("Confirm")',
    'text=/accept/i',
    'text=/continue/i',
  ],
};

/**
 * Generic OIDC/SSO button selectors
 * Used when service-specific selectors don't match
 */
const oidcGeneric = [
  'button:has-text("Authelia")',
  'a:has-text("Authelia")',
  'text=/single sign on/i',
  'text=/sign in with/i',
  'text=/continue with/i',
  'text=/oidc/i',
  'text=/open id/i',
  'text=/oauth/i',
  'text=/sso/i',
  'button:has-text("SSO")',
];

/**
 * Service-specific OIDC button selectors
 * Prioritized over generic selectors for better accuracy
 */
const oidcByService = {
  grafana: [
    'button:has-text("Sign in with Authelia")',
    'button:has-text("Sign in with OAuth")',
  ],

  'open-webui': [
    'text=/Continue with Authelia/i',  // Not a button tag - styled div/span
    'button:has-text("Continue with Authelia")',
    'button:has-text("Login with OIDC")',
    'text=/Login with OIDC/i',
  ],

  jupyterhub: [
    'button:has-text("Sign in with")',
    'a:has-text("Sign in with")',
  ],

  outline: [
    'button:has-text("Continue with Authelia")',
    'button:has-text("Continue with OpenID")',
  ],

  planka: [
    'button:has-text("Sign in with OpenID")',
    'button:has-text("Sign in with Authelia")',
  ],

  vaultwarden: [
    'button:has-text("Single Sign-On")',
    'button:has-text("Enterprise Single Sign-On")',
  ],

  nextcloud: [
    'button:has-text("Log in with OpenID")',
    'button:has-text("Log in with Authelia")',
  ],

  litellm: [
    'button:has-text("Continue with Authelia")',
    'button:has-text("Login with OIDC")',
  ],

  localai: [
    // LocalAI uses forward-auth, no OIDC button
  ],
};

/**
 * First-run wizard/onboarding skip buttons
 */
const wizardSkip = [
  'button:has-text("Skip")',
  'button:has-text("Next")',
  'button:has-text("Finish")',
  'button:has-text("Done")',
  'button:has-text("Continue")',
  'text=/skip getting started/i',
];

/**
 * Get OIDC button selectors for a service (specific + generic)
 */
function getOidcSelectors(serviceName) {
  return [
    ...(oidcByService[serviceName] || []),
    ...oidcGeneric,
  ];
}

module.exports = {
  authelia,
  oidcGeneric,
  oidcByService,
  wizardSkip,
  getOidcSelectors,
};
