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
 * OIDC services (OAuth via Authelia/LDAP)
 */
const oidcServices = [
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
    uiMarkers: ['text=/Create project/i', 'text=/System Administrator/i', 'text=/PLANKA/i'],
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
 * Forward-auth services (protected by Authelia forward-auth)
 */
const forwardAuthServices = [
  {
    name: 'filebrowser',
    url: 'https://filebrowser.project-saturn.com',
    authType: 'forward-auth',
    uiMarkers: ['text=/My Files/i', 'text=/Settings/i'],
  },
  {
    name: 'homeassistant',
    url: 'https://homeassistant.project-saturn.com',
    authType: 'forward-auth',
    uiMarkers: ['text=/Overview/i', 'text=/Energy/i', 'text=/Map/i'],
  },
  {
    name: 'kopia',
    url: 'https://kopia.project-saturn.com',
    authType: 'forward-auth',
    uiMarkers: ['text=/Repository/i', 'text=/Snapshots/i', 'text=/Policies/i'],
  },
  {
    name: 'dockge',
    url: 'https://dockge.project-saturn.com',
    authType: 'forward-auth',
    hasFirstRunSetup: true,
    uiMarkers: ['text=/Stacks/i', 'text=/Dockge/i'],
  },
  {
    name: 'homepage',
    url: 'https://homepage.project-saturn.com',
    authType: 'forward-auth',
    uiMarkers: ['text=/Services/i', 'text=/Bookmarks/i'],
  },
  {
    name: 'lam',
    url: 'https://lam.project-saturn.com',
    authType: 'forward-auth',
    uiMarkers: ['text=/LDAP Account Manager/i', 'text=/Users/i', 'text=/Groups/i'],
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
  ...oidcServices,
  ...forwardAuthServices,
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
 * Get OIDC service by name
 */
function getOidcService(name) {
  return oidcServices.find(s => s.name === name) || null;
}

/**
 * Get forward-auth service by name
 */
function getForwardAuthService(name) {
  return forwardAuthServices.find(s => s.name === name) || null;
}

/**
 * Get services by filter criteria
 * @param {Object} filters
 * @param {string} [filters.authType] - Filter by auth type
 * @returns {ServiceConfig[]}
 */
function getServices(filters = {}) {
  let services = [...allServices];

  if (filters.authType) {
    services = services.filter(s => s.authType === filters.authType);
  }

  return services;
}

module.exports = {
  oidcServices,
  forwardAuthServices,
  aiServices,
  allServices,
  getService,
  getOidcService,
  getForwardAuthService,
  getServices,
};
