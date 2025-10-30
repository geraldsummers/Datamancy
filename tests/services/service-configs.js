// tests/services/service-configs.js
// Service definitions: URLs, auth types, UI markers

/**
 * Service configuration object
 * @typedef {Object} ServiceConfig
 * @property {string} name - Service name (must match subdomain)
 * @property {string} url - Full service URL
 * @property {boolean} usesOAuth - True if OIDC/OAuth, false if forward-auth or manual
 * @property {string[]} [uiMarkers] - Playwright selectors for post-auth UI verification
 * @property {boolean} [optionalWizards] - True if service has first-run wizards to skip
 * @property {string} [authType] - 'oidc' or 'forward-auth' (optional, for clarity)
 */

/**
 * Main services (OIDC via Authelia/LDAP)
 */
const mainServices = [
  {
    name: 'grafana',
    url: 'https://grafana.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Dashboards/i', 'text=/Explore/i'],
  },
  {
    name: 'open-webui',
    url: 'https://open-webui.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/New Chat/i', 'text=/Models/i'],
  },
  {
    name: 'jupyterhub',
    url: 'https://jupyterhub.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Launcher/i', 'text=/File/i', 'text=/Notebook/i'],
  },
  {
    name: 'outline',
    url: 'https://outline.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Documents/i', 'text=/Collections/i'],
  },
  {
    name: 'planka',
    url: 'https://planka.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Boards/i', 'text=/Projects/i'],
  },
  {
    name: 'vaultwarden',
    url: 'https://vaultwarden.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Vaultwarden/i', 'text=/Vault/i'],
  },
  {
    name: 'nextcloud',
    url: 'https://nextcloud.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    optionalWizards: true,
    uiMarkers: ['text=/Files/i', 'text=/Photos/i'],
  },
];

/**
 * Non-OIDC services (reachability-only or manual login)
 */
const nonOidcServices = [
  {
    name: 'filebrowser',
    url: 'https://filebrowser.project-saturn.com',
    usesOAuth: false,
    uiMarkers: ['text=/File Browser/i', 'text=/Sign in/i'],
  },
  {
    name: 'homeassistant',
    url: 'https://homeassistant.project-saturn.com',
    usesOAuth: false,
    uiMarkers: ['text=/Home Assistant/i', 'text=/Overview/i'],
  },
  {
    name: 'kopia',
    url: 'https://kopia.project-saturn.com',
    usesOAuth: false,
    uiMarkers: ['text=/Kopia/i', 'text=/Repository/i', 'text=/Snapshots/i'],
  },
];

/**
 * AI services
 */
const aiServices = [
  {
    name: 'open-webui',
    url: 'https://open-webui.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/New Chat/i', 'text=/Models/i'],
  },
  {
    name: 'litellm',
    url: 'https://litellm.project-saturn.com',
    usesOAuth: true,
    authType: 'oidc',
    uiMarkers: ['text=/Models/i', 'text=/API/i'],
  },
  {
    name: 'localai',
    url: 'https://localai.project-saturn.com',
    usesOAuth: false, // Forward-auth via Authelia
    authType: 'forward-auth',
    uiMarkers: ['text=/LocalAI/i', 'text=/API/i'],
  },
];

/**
 * All services combined
 */
const allServices = [
  ...mainServices,
  ...nonOidcServices,
];

/**
 * Get service config by name
 * @param {string} name - Service name
 * @returns {ServiceConfig|null}
 */
function getService(name) {
  return allServices.find(s => s.name === name) || null;
}

/**
 * Get services by filter criteria
 * @param {Object} filters
 * @param {boolean} [filters.usesOAuth] - Filter by OAuth usage
 * @param {string} [filters.authType] - Filter by auth type
 * @returns {ServiceConfig[]}
 */
function getServices(filters = {}) {
  let services = [...allServices];

  if (filters.usesOAuth !== undefined) {
    services = services.filter(s => s.usesOAuth === filters.usesOAuth);
  }

  if (filters.authType) {
    services = services.filter(s => s.authType === filters.authType);
  }

  return services;
}

module.exports = {
  mainServices,
  nonOidcServices,
  aiServices,
  allServices,
  getService,
  getServices,
};
