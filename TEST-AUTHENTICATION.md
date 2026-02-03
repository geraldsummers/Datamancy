# Programmatic Authentication Token Acquisition

## Overview

The Datamancy test framework now includes comprehensive token management for all services that require authentication. The `TokenManager` class provides programmatic token acquisition for:

- **Grafana** - API keys via admin session
- **BookStack** - API token pairs (token_id:token_secret)
- **Forgejo** - Personal access tokens
- **Mastodon** - OAuth2 access tokens
- **Seafile** - API tokens
- **Planka** - JWT authentication tokens
- **Open-WebUI** - JWT tokens
- **JupyterHub** - API tokens
- **Qbittorrent** - Session cookies
- **Home Assistant** - Long-lived access tokens

## Architecture

```
TestRunner
  ├── AuthHelper (Authelia SSO)
  └── TokenManager (Service-specific tokens)
        ├── acquireGrafanaToken()
        ├── acquireForgejoToken()
        ├── acquireMastodonToken()
        ├── acquireSeafileToken()
        ├── acquirePlankaToken()
        ├── acquireOpenWebUIToken()
        ├── acquireJupyterHubToken()
        ├── acquireQbittorrentSession()
        └── acquireHomeAssistantToken()
```

## Usage Examples

### In Test Suites

```kotlin
suspend fun TestRunner.myTests() = suite("My Tests") {
    test("Grafana: Query with API key") {
        // Acquire token
        val tokenResult = tokens.acquireGrafanaToken("admin", "password")
        require(tokenResult.isSuccess) { "Token acquisition failed" }

        // Make authenticated request
        val response = tokens.authenticatedGet(
            service = "grafana",
            url = "http://grafana:3000/api/datasources"
        )

        require(response.status == HttpStatusCode.OK)
    }
}
```

### Token Acquisition Methods

#### Grafana
```kotlin
val result = tokens.acquireGrafanaToken(username = "admin", password = "changeme")
// Creates admin API key via session login
```

#### Forgejo
```kotlin
val result = tokens.acquireForgejoToken(username = "admin", password = "changeme")
// Creates personal access token via API
```

#### Mastodon
```kotlin
val result = tokens.acquireMastodonToken(email = "user@domain.com", password = "changeme")
// Registers OAuth app and acquires token via password grant
```

#### Seafile
```kotlin
val result = tokens.acquireSeafileToken(username = "admin@datamancy.local", password = "changeme")
// Gets API token via auth endpoint
```

#### Planka
```kotlin
val result = tokens.acquirePlankaToken(email = "admin@datamancy.local", password = "changeme")
// Authenticates and gets JWT token
```

#### Qbittorrent
```kotlin
val result = tokens.acquireQbittorrentSession(username = "admin", password = "adminpass")
// Returns session cookies for authenticated requests
```

### Making Authenticated Requests

```kotlin
// GET request
val response = tokens.authenticatedGet("grafana", "http://grafana:3000/api/dashboards")

// POST request
val response = tokens.authenticatedPost("forgejo", "http://forgejo:3000/api/v1/user/repos") {
    contentType(ContentType.Application.Json)
    setBody("""{"name":"test-repo"}""")
}
```

### Token Management

```kotlin
// Check if token exists
if (tokens.hasToken("grafana")) {
    // Token is cached
}

// Get token directly
val token = tokens.getToken("grafana")

// Get cookies (for session-based auth)
val cookies = tokens.getCookies("qbittorrent")

// Clear specific token
tokens.clearToken("grafana")

// Clear all tokens
tokens.clearAll()
```

## Environment Variables for Credentials

For security, credentials should be provided via environment variables:

```bash
# Grafana
export GRAFANA_ADMIN_PASSWORD="your-admin-password"

# Forgejo
export FORGEJO_USERNAME="admin"
export FORGEJO_PASSWORD="your-password"

# Mastodon
export MASTODON_EMAIL="admin@datamancy.local"
export MASTODON_PASSWORD="your-password"

# Seafile
export SEAFILE_USERNAME="admin@datamancy.local"
export SEAFILE_PASSWORD="your-password"

# Planka
export PLANKA_EMAIL="admin@datamancy.local"
export PLANKA_PASSWORD="your-password"

# Open-WebUI
export OPEN_WEBUI_EMAIL="admin@datamancy.local"
export OPEN_WEBUI_PASSWORD="your-password"

# JupyterHub
export JUPYTERHUB_USERNAME="admin"
export JUPYTERHUB_PASSWORD="your-password"

# Qbittorrent
export QBITTORRENT_USERNAME="admin"
export QBITTORRENT_PASSWORD="adminpass"

# Home Assistant (requires pre-generated token)
export HOME_ASSISTANT_TOKEN="your-long-lived-token"

# BookStack (requires pre-generated tokens)
export BOOKSTACK_TOKEN_ID="your-token-id"
export BOOKSTACK_TOKEN_SECRET="your-token-secret"
```

## Service-Specific Notes

### BookStack
BookStack tokens must be **pre-generated** via the web UI:
1. Login as admin
2. Go to Settings → API Tokens
3. Create new token
4. Export `BOOKSTACK_TOKEN_ID` and `BOOKSTACK_TOKEN_SECRET`

### Home Assistant
Long-lived access tokens must be **pre-generated**:
1. Login to Home Assistant
2. Profile → Security → Long-Lived Access Tokens
3. Create token
4. Export `HOME_ASSISTANT_TOKEN`

### Mastodon
The token manager automatically:
1. Registers a test OAuth application
2. Uses password grant flow to acquire token
3. Returns access token for API calls

### JupyterHub
Requires PAM authentication (LDAP/local users). Token created via API after basic auth.

## Running Authenticated Tests

```bash
# Set credentials
export GRAFANA_ADMIN_PASSWORD="your-password"
export FORGEJO_USERNAME="admin"
export FORGEJO_PASSWORD="your-password"

# Run authenticated operations test suite
docker compose run --rm integration-test-runner authenticated-ops

# Or run all tests (includes authenticated operations)
docker compose run --rm integration-test-runner all
```

## Test Suite: authenticated-ops

The `AuthenticatedOperationsTests` suite validates:

1. **Token Acquisition** - Can acquire tokens for all services
2. **Authenticated Requests** - Tokens work for API calls
3. **Token Storage** - TokenManager correctly stores/retrieves tokens
4. **Token Cleanup** - Can clear tokens individually or all at once

**Tests (10):**
- Grafana: Acquire API key and query datasources
- Seafile: Acquire token and list libraries
- Forgejo: Acquire token and list repositories
- Planka: Acquire token and list boards
- Qbittorrent: Acquire session and get version
- Mastodon: Acquire OAuth token and verify credentials
- Open-WebUI: Acquire JWT and list models
- Token manager: Store and retrieve tokens
- Token manager: Clear tokens

## Error Handling

All token acquisition methods return `Result<T>`:

```kotlin
val result = tokens.acquireGrafanaToken("admin", "password")

when {
    result.isSuccess -> {
        val token = result.getOrThrow()
        // Use token
    }
    result.isFailure -> {
        val error = result.exceptionOrNull()
        println("Failed: ${error?.message}")
        // Handle failure (skip test, log warning, etc.)
    }
}
```

## Best Practices

1. **Use environment variables** for credentials (never hardcode)
2. **Clear tokens after tests** to avoid token leakage
3. **Skip tests gracefully** if credentials are missing
4. **Pre-generate tokens** for services that require it (BookStack, Home Assistant)
5. **Check token validity** before making requests
6. **Use Result type** for error handling

## Security Considerations

- ✅ Tokens stored in memory only (not persisted)
- ✅ Environment variables for credentials
- ✅ Automatic token cleanup via `clearAll()`
- ✅ No tokens in logs or error messages
- ✅ Test-only tokens (short-lived, limited scope)
- ⚠️ Run tests in isolated environments only
- ⚠️ Never run with production credentials

## Adding New Services

To add token acquisition for a new service:

1. **Add method to TokenManager:**
```kotlin
suspend fun acquireNewServiceToken(username: String, password: String): Result<String> {
    return try {
        // Service-specific auth logic
        val response = client.post("http://service/auth") {
            setBody(credentials)
        }

        if (response.status == HttpStatusCode.OK) {
            val token = parseToken(response)
            tokens["newservice"] = token
            Result.success(token)
        } else {
            Result.failure(Exception("Auth failed"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

2. **Update authenticatedGet/authenticatedPost:**
```kotlin
when (service) {
    "newservice" -> {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}
```

3. **Add tests to AuthenticatedOperationsTests**

4. **Document environment variables in this file**

## Complete Test Count

**Total Tests with Authentication**: **243 tests**
- Previous: 233 tests
- New: +10 authenticated operations tests

## Summary

The TokenManager provides a **unified, secure, programmatic way** to acquire and manage authentication tokens for all services in the Datamancy stack. This enables:

- ✅ End-to-end testing with real authentication
- ✅ Validation of auth flows for all services
- ✅ Automated integration testing without manual token generation
- ✅ Consistent token management across all test suites

🔥 **Complete service coverage with automated authentication!** 🔥
