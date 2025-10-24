const express = require('express');
const session = require('express-session');
const cookieParser = require('cookie-parser');
const { Issuer, generators } = require('openid-client');

const app = express();
const PORT = process.env.PORT || 4180;
const BASE_DOMAIN = process.env.BASE_DOMAIN || 'lab.localhost';
const DEX_ISSUER = process.env.DEX_ISSUER || `https://dex.${BASE_DOMAIN}`;
const DEX_INTERNAL = process.env.DEX_INTERNAL || 'http://dex:5556'; // Internal HTTP for discovery
const CLIENT_ID = process.env.CLIENT_ID || 'forward-auth';
const CLIENT_SECRET = process.env.CLIENT_SECRET || 'forward_auth_secret_change_me';
const SESSION_SECRET = process.env.SESSION_SECRET || 'forward_auth_session_secret_change_me_min_32_chars';
const CALLBACK_URL = process.env.CALLBACK_URL || `https://auth.${BASE_DOMAIN}/callback`;

let oidcClient;

// Initialize OIDC client
async function initOIDC() {
  try {
    // Discover via internal HTTP endpoint, but issuer must match public HTTPS
    const issuer = await Issuer.discover(DEX_INTERNAL);
    console.log('Discovered issuer:', issuer.issuer);

    oidcClient = new issuer.Client({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      redirect_uris: [CALLBACK_URL],
      response_types: ['code'],
    });

    console.log('OIDC client initialized');
  } catch (error) {
    console.error('Failed to initialize OIDC:', error);
    setTimeout(initOIDC, 5000); // Retry after 5 seconds
  }
}

app.use(cookieParser());
app.use(session({
  secret: SESSION_SECRET,
  resave: false,
  saveUninitialized: false,
  cookie: {
    secure: false, // Set to true if using HTTPS internally
    httpOnly: true,
    maxAge: 24 * 60 * 60 * 1000 // 24 hours
  }
}));

// Health check
app.get('/health', (req, res) => {
  res.status(200).send('OK');
});

// Forward auth validation endpoint
app.get('/validate', async (req, res) => {
  if (!oidcClient) {
    return res.status(503).send('Auth service not ready');
  }

  // Check if user has valid session
  if (req.session.userinfo) {
    // Check for required groups (passed via X-Forwarded-Required-Groups header from Traefik)
    const requiredGroupsHeader = req.headers['x-forwarded-required-groups'] || req.query.required_groups || '';
    const requiredGroups = requiredGroupsHeader ? requiredGroupsHeader.split(',').map(g => g.trim()).filter(Boolean) : [];

    if (requiredGroups.length > 0) {
      const userGroups = req.session.userinfo.groups || [];
      const hasRequiredGroup = requiredGroups.some(required => userGroups.includes(required));

      if (!hasRequiredGroup) {
        console.log(`Access denied: User ${req.session.userinfo.email} not in required groups [${requiredGroups.join(',')}]. User groups: [${userGroups.join(',')}]`);
        return res.status(403).send('Forbidden: Insufficient permissions');
      }
    }

    // User is authenticated and authorized, pass through with headers
    res.set('Remote-User', req.session.userinfo.preferred_username || req.session.userinfo.email);
    res.set('Remote-Email', req.session.userinfo.email);
    res.set('Remote-Name', req.session.userinfo.name);
    if (req.session.userinfo.groups) {
      res.set('Remote-Groups', req.session.userinfo.groups.join(','));
    }
    return res.status(200).send('OK');
  }

  // User not authenticated, redirect to login
  const originalUrl = req.headers['x-forwarded-uri'] || req.headers['x-original-url'] || '/';
  const originalHost = req.headers['x-forwarded-host'] || req.headers['host'] || BASE_DOMAIN;

  // Store the original URL to redirect back after login
  req.session.returnTo = `https://${originalHost}${originalUrl}`;
  await req.session.save();

  // Generate auth URL
  const code_verifier = generators.codeVerifier();
  const code_challenge = generators.codeChallenge(code_verifier);

  req.session.code_verifier = code_verifier;
  await req.session.save();

  const authUrl = oidcClient.authorizationUrl({
    scope: 'openid profile email groups',
    code_challenge,
    code_challenge_method: 'S256',
  });

  res.status(401).header('Location', authUrl).send('Unauthorized');
});

// OAuth callback
app.get('/callback', async (req, res) => {
  if (!oidcClient) {
    return res.status(503).send('Auth service not ready');
  }

  try {
    const params = oidcClient.callbackParams(req);
    const tokenSet = await oidcClient.callback(CALLBACK_URL, params, {
      code_verifier: req.session.code_verifier,
    });

    const userinfo = await oidcClient.userinfo(tokenSet);

    req.session.userinfo = userinfo;
    req.session.tokenSet = tokenSet;
    delete req.session.code_verifier;

    const returnTo = req.session.returnTo || `https://${BASE_DOMAIN}`;
    delete req.session.returnTo;

    await req.session.save();

    res.redirect(returnTo);
  } catch (error) {
    console.error('Callback error:', error);
    res.status(500).send('Authentication failed');
  }
});

// Logout endpoint
app.get('/logout', async (req, res) => {
  const returnTo = req.query.returnTo || `https://${BASE_DOMAIN}`;
  req.session.destroy();
  res.redirect(`${DEX_ISSUER}/logout?redirect_uri=${encodeURIComponent(returnTo)}`);
});

// Start server
initOIDC().then(() => {
  app.listen(PORT, () => {
    console.log(`Forward auth service listening on port ${PORT}`);
    console.log(`Dex issuer: ${DEX_ISSUER}`);
    console.log(`Callback URL: ${CALLBACK_URL}`);
  });
});
