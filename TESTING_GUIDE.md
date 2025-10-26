# Caddy Security Testing Guide

## Quick Start

### 1. Start the stack
```bash
docker compose --profile infra --profile phase2 --profile phase3 up -d
```

### 2. Run all tests
```bash
./scripts/test-caddy-security.sh
```

### 3. Run specific test suites
```bash
# RBAC and forward-auth tests only
./scripts/test-caddy-security.sh rbac

# Grafana OIDC tests only
./scripts/test-caddy-security.sh oidc-grafana

# LibreChat OIDC tests only
./scripts/test-caddy-security.sh oidc-librechat
```

## Manual Browser Testing

### Test Users
| Username | Password | Role | Can Access |
|----------|----------|------|------------|
| admin | password | Admin | All services |
| testuser | password | User | Grafana, Mailpit, LibreChat, LocalAI |

### Test Scenarios

#### 1. Admin Access to Protected Services
1. Navigate to: `https://stack.local/prometheus/`
2. Should redirect to: `https://stack.local/auth/...`
3. Login with: `admin` / `password`
4. Should redirect back to Prometheus
5. Verify: Prometheus UI loads correctly

#### 2. User Blocked from Admin Services
1. Clear browser cookies
2. Navigate to: `https://stack.local/prometheus/`
3. Login with: `testuser` / `password`
4. Should see: Access Denied or 403 error
5. Navigate to: `https://stack.local/grafana/`
6. Should work: Grafana loads correctly

#### 3. Grafana OIDC Flow
1. Clear browser cookies
2. Navigate to: `https://stack.local/grafana/`
3. Look for: "Sign in with Datamancy SSO" button
4. Click SSO button
5. Should redirect to: Caddy Security login
6. Login with: `admin` / `password`
7. Should redirect back to: Grafana dashboard
8. Verify: User is logged in (check top-right corner)

#### 4. LibreChat OIDC Flow
1. Clear browser cookies
2. Navigate to: `https://stack.local/librechat/`
3. Look for: "Continue with Datamancy SSO" button
4. Click SSO button
5. Should redirect to: Caddy Security login
6. Login with: `admin` / `password`
7. Should redirect back to: LibreChat interface
8. Verify: Chat interface loads

#### 5. Session Persistence
1. Login to Prometheus as admin
2. Navigate to: `https://stack.local/alertmanager/`
3. Should NOT ask for login again
4. Verify: Alertmanager loads directly

#### 6. Metrics Bypass Auth
1. Clear browser cookies (be logged out)
2. Navigate to: `https://stack.local/prometheus/metrics`
3. Should work: Prometheus metrics displayed without login
4. Navigate to: `https://stack.local/alertmanager/metrics`
5. Should work: Alertmanager metrics displayed without login

## Test Results

### Test Output Locations
- **JUnit XML**: `data/tests/caddy-security-results.xml`
- **HTML Report**: `data/tests/caddy-security-html-report/`
- **Screenshots**: `data/tests/test-results/`
- **Videos**: `data/tests/test-results/` (on failure)

### View HTML Report
```bash
cd tests
npm run report
```

## Troubleshooting

### Tests Failing: "Service not running"
```bash
# Check service status
docker compose ps

# View logs
docker compose logs caddy
docker compose logs openldap
```

### Tests Failing: "Unable to authenticate"
```bash
# Verify LDAP users exist
docker compose exec openldap ldapsearch -x -H ldap://localhost \
  -b "dc=datamancy,dc=local" \
  -D "cn=admin,dc=datamancy,dc=local" \
  -w admin_password \
  "(objectClass=inetOrgPerson)" uid cn mail

# Check Caddy can connect to LDAP
docker compose logs caddy | grep -i ldap
```

### Tests Failing: "Invalid JWT token"
```bash
# Verify JWT_SHARED_KEY is set
docker compose exec caddy env | grep JWT_SHARED_KEY

# Check Caddy configuration
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile
```

### OIDC Flow Fails
```bash
# Check OIDC configuration in Caddy logs
docker compose logs caddy | grep -i oidc

# Verify Grafana OIDC settings
docker compose exec grafana env | grep GF_AUTH_GENERIC_OAUTH

# Check callback URLs match
docker compose logs caddy | grep redirect_uri
```

### Screenshots Show Blank Pages
```bash
# Check Caddy is serving correctly
curl -k https://stack.local/

# Verify certificates
ls -la certs/

# Check for JavaScript errors (run test with --headed)
cd tests
npx playwright test --headed --config playwright.config.caddy-security.js
```

## Common Issues

### Issue: "ERR_CERT_AUTHORITY_INVALID"
**Solution**: Tests are configured with `ignoreHTTPSErrors: true`, but if testing manually, trust the CA certificate:
```bash
# Linux
sudo cp certs/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt
sudo update-ca-certificates

# macOS
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain certs/ca.crt
```

### Issue: "Login form not found"
**Solution**: Check if forward-auth is triggering before reaching the service:
```bash
# Test without following redirects
curl -k -I https://stack.local/prometheus/

# Should see Location header pointing to /auth
```

### Issue: "Access Denied for admin user"
**Solution**: Check LDAP group membership:
```bash
docker compose exec openldap ldapsearch -x -H ldap://localhost \
  -b "dc=datamancy,dc=local" \
  -D "cn=admin,dc=datamancy,dc=local" \
  -w admin_password \
  "(uid=admin)" memberOf
```

## Performance Testing

### Load Test Authentication
```bash
# Install k6 or ab (Apache Bench)
# Test login endpoint
ab -n 100 -c 10 -k https://stack.local/auth/
```

### Monitor Resource Usage
```bash
docker stats caddy openldap

# Watch for:
# - CPU spikes during authentication
# - Memory growth with concurrent sessions
# - Network I/O for LDAP queries
```

## CI/CD Integration

### Run tests in CI
```bash
# In CI pipeline
docker compose --profile infra --profile phase2 --profile phase3 up -d
docker compose run --rm test-runner npx playwright test --config playwright.config.caddy-security.js
```

### Parse results
```bash
# JUnit XML can be consumed by most CI systems
cat data/tests/caddy-security-results.xml
```

## Next Steps After Testing

1. ✅ Review test results and screenshots
2. ✅ Perform manual browser testing for each scenario
3. ✅ Check logs for errors or warnings
4. ⬜ Load test authentication under realistic traffic
5. ⬜ Update production secrets (JWT_SHARED_KEY)
6. ⬜ Remove Authelia service from docker-compose.yml
7. ⬜ Document operational procedures for production

## Additional Resources

- [Caddy Security Plugin Docs](https://github.com/greenpau/caddy-security)
- [Playwright Documentation](https://playwright.dev)
- [LDAP Testing Guide](https://www.openldap.org/doc/admin24/quickstart.html)
- [JWT Debugging](https://jwt.io)
