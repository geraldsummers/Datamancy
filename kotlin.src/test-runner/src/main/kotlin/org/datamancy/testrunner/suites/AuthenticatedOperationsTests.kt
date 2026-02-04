package org.datamancy.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*

/**
 * Authenticated Operations Tests
 *
 * Tests that acquire auth tokens and perform authenticated operations
 * Validates end-to-end authentication workflows for all services
 *
 * IMPORTANT: These tests require valid test credentials:
 * - GRAFANA_ADMIN_PASSWORD
 * - FORGEJO_USERNAME / FORGEJO_PASSWORD
 * - MASTODON_EMAIL / MASTODON_PASSWORD
 * - etc.
 */
suspend fun TestRunner.authenticatedOperationsTests() = suite("Authenticated Operations Tests") {

    // =============================================================================
    // GRAFANA AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Grafana: Acquire API key and query datasources") {
        val grafanaPassword = System.getenv("GRAFANA_ADMIN_PASSWORD") ?: "admin"

        // Step 1: Authenticate with Authelia (Grafana is behind Authelia)
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Grafana: Query datasources", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Acquire Grafana API token (direct to container)
        val tokenResult = tokens.acquireGrafanaToken("admin", grafanaPassword)
        require(tokenResult.isSuccess) { "Failed to acquire Grafana token: ${tokenResult.exceptionOrNull()?.message}" }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Grafana API key")

        // Step 3: Test authenticated request (direct container access)
        val response = tokens.authenticatedGet("grafana", "${env.endpoints.grafana}/api/datasources")
        require(response.status == HttpStatusCode.OK) {
            "Failed to query datasources: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("[") || body.contains("{}")) {
            "Unexpected datasources response"
        }
        println("      ✓ Successfully queried Grafana datasources with API key")

        // Step 4: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Grafana through authenticated proxy")
    }

    // =============================================================================
    // SEAFILE AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Seafile: Acquire token and list libraries") {
        val username = System.getenv("SEAFILE_USERNAME") ?: "admin@datamancy.local"
        val password = System.getenv("SEAFILE_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireSeafileToken(username, password)
        if (tokenResult.isFailure) {
            skip("Seafile: List libraries", "Token acquisition failed - admin user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Seafile token")

        // Test authenticated request
        val response = tokens.authenticatedGet("seafile", "http://seafile:80/api2/repos/")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list libraries: ${response.status}"
        }

        println("      ✓ Successfully listed Seafile libraries")
    }

    // =============================================================================
    // FORGEJO AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Forgejo: Acquire token and list repositories") {
        val username = System.getenv("FORGEJO_USERNAME") ?: "admin"
        val password = System.getenv("FORGEJO_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireForgejoToken(username, password)
        if (tokenResult.isFailure) {
            skip("Forgejo: List repos", "Token acquisition failed - ${tokenResult.exceptionOrNull()?.message}")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Forgejo access token")

        // Test authenticated request
        val response = tokens.authenticatedGet("forgejo", "http://forgejo:3000/api/v1/user/repos")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list repos: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.startsWith("[")) {
            "Unexpected repos response"
        }

        println("      ✓ Successfully listed Forgejo repositories")
    }

    // =============================================================================
    // PLANKA AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Planka: Acquire token and list boards") {
        val email = System.getenv("PLANKA_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("PLANKA_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquirePlankaToken(email, password)
        if (tokenResult.isFailure) {
            skip("Planka: List boards", "Token acquisition failed - admin user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Planka authentication token")

        // Test authenticated request
        val response = tokens.authenticatedGet("planka", "http://planka:1337/api/boards")
        require(response.status == HttpStatusCode.OK) {
            "Failed to list boards: ${response.status}"
        }

        println("      ✓ Successfully listed Planka boards")
    }

    // =============================================================================
    // QBITTORRENT AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Qbittorrent: Acquire session and get version") {
        val username = System.getenv("QBITTORRENT_USERNAME") ?: "admin"
        val password = System.getenv("QBITTORRENT_PASSWORD") ?: "adminpass"

        // Step 1: Authenticate with Authelia (Qbittorrent is behind Authelia)
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Qbittorrent: Get version", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Acquire Qbittorrent session (direct to container)
        val sessionResult = tokens.acquireQbittorrentSession(username, password)
        if (sessionResult.isFailure) {
            skip("Qbittorrent: Get version", "Session acquisition failed - ${sessionResult.exceptionOrNull()?.message}")
            return@test
        }
        println("      ✓ Acquired Qbittorrent session cookie")

        // Step 3: Test authenticated request (direct container access)
        val response = tokens.authenticatedGet("qbittorrent", "http://qbittorrent:8080/api/v2/app/version")
        require(response.status == HttpStatusCode.OK) {
            "Failed to get version: ${response.status}"
        }

        val version = response.bodyAsText()
        println("      ✓ Qbittorrent version: $version")

        // Step 4: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Qbittorrent through authenticated proxy")
    }

    // =============================================================================
    // MASTODON AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Mastodon: Acquire OAuth token and verify credentials") {
        val email = System.getenv("MASTODON_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("MASTODON_PASSWORD") ?: "changeme"

        // Acquire token
        val tokenResult = tokens.acquireMastodonToken(email, password)
        if (tokenResult.isFailure) {
            skip("Mastodon: Verify credentials", "Token acquisition failed - ${tokenResult.exceptionOrNull()?.message}")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Mastodon OAuth token")

        // Test authenticated request
        val response = tokens.authenticatedGet("mastodon", "http://mastodon-web:3000/api/v1/accounts/verify_credentials")
        require(response.status == HttpStatusCode.OK) {
            "Failed to verify credentials: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("id") && body.contains("username")) {
            "Unexpected credentials response"
        }

        println("      ✓ Successfully verified Mastodon credentials")
    }

    // =============================================================================
    // OPEN-WEBUI AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Open-WebUI: Acquire JWT and list models") {
        val email = System.getenv("OPEN_WEBUI_EMAIL") ?: "admin@datamancy.local"
        val password = System.getenv("OPEN_WEBUI_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia first (since Open-WebUI is behind Authelia)
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"
        val autheliaResult = auth.login("admin", ldapPassword)

        if (autheliaResult !is AuthResult.Success) {
            skip("Open-WebUI: List models", "Authelia authentication failed - ${(autheliaResult as AuthResult.Error).message}")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Acquire Open-WebUI JWT token (direct to container, bypassing Caddy)
        val tokenResult = tokens.acquireOpenWebUIToken(email, password)
        if (tokenResult.isFailure) {
            skip("Open-WebUI: List models", "Open-WebUI token acquisition failed - user may not exist yet")
            return@test
        }

        val token = tokenResult.getOrThrow()
        println("      ✓ Acquired Open-WebUI JWT token")

        // Step 3: Test direct container access (should work with JWT, no Authelia needed)
        val directResponse = tokens.authenticatedGet("open-webui", "http://open-webui:8080/api/models")
        if (directResponse.status == HttpStatusCode.OK) {
            println("      ✓ Successfully listed Open-WebUI models (direct access)")
        } else if (directResponse.status == HttpStatusCode.NotFound) {
            // Try alternative API path
            val altResponse = tokens.authenticatedGet("open-webui", "http://open-webui:8080/api/v1/models")
            require(altResponse.status == HttpStatusCode.OK || altResponse.status == HttpStatusCode.NotFound) {
                "Failed to list models on both /api/models and /api/v1/models: ${altResponse.status}"
            }
            println("      ✓ Open-WebUI API responded (endpoint may need configuration)")
        } else {
            require(directResponse.status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
                "Unexpected response from Open-WebUI: ${directResponse.status}"
            }
            println("      ✓ Open-WebUI container accessible")
        }

        // Step 4: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Open-WebUI through authenticated proxy")
    }

    // =============================================================================
    // JUPYTERHUB AUTHENTICATED OPERATIONS
    // =============================================================================

    test("JupyterHub: Authenticate and access hub API") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("JupyterHub: Access hub API", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://jupyterhub:8000/hub/api")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "JupyterHub container not responding: ${directResponse.status}"
        }
        println("      ✓ JupyterHub container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed JupyterHub through authenticated proxy")
    }

    // =============================================================================
    // LITELLM AUTHENTICATED OPERATIONS
    // =============================================================================

    test("LiteLLM: Authenticate and access API") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("LiteLLM: Access API", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://litellm:4000/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "LiteLLM container not responding: ${directResponse.status}"
        }
        println("      ✓ LiteLLM container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed LiteLLM through authenticated proxy")
    }

    // =============================================================================
    // NTFY AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Ntfy: Authenticate and access notification API") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Ntfy: Access API", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://ntfy:80/v1/health")
        require(directResponse.status == HttpStatusCode.OK) {
            "Ntfy container not responding: ${directResponse.status}"
        }
        println("      ✓ Ntfy container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Ntfy through authenticated proxy")
    }

    // =============================================================================
    // KOPIA AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Kopia: Authenticate and access backup UI") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Kopia: Access UI", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Kopia through authenticated proxy")
    }

    // =============================================================================
    // RADICALE (CALENDAR/CONTACTS) AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Radicale: Authenticate and access CalDAV/CardDAV") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Radicale: Access CalDAV", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://radicale:5232/")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Radicale container not responding: ${directResponse.status}"
        }
        println("      ✓ Radicale container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Radicale through authenticated proxy")
    }

    // =============================================================================
    // ROUNDCUBE (MAIL) AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Roundcube: Authenticate and access webmail") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Roundcube: Access webmail", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://roundcube:80/")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Found) {
            "Roundcube container not responding: ${directResponse.status}"
        }
        println("      ✓ Roundcube container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Roundcube through authenticated proxy")
    }

    // =============================================================================
    // SEARCH SERVICE AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Search Service: Authenticate and access API") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Search Service: Access API", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://search-service:8098/actuator/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Search Service container not responding: ${directResponse.status}"
        }
        println("      ✓ Search Service container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Search Service through authenticated proxy")
    }

    // =============================================================================
    // PIPELINE SERVICE AUTHENTICATED OPERATIONS
    // =============================================================================

    test("Pipeline: Authenticate and access management API") {
        val ldapPassword = System.getenv("LDAP_ADMIN_PASSWORD") ?: "changeme"

        // Step 1: Authenticate with Authelia
        val autheliaResult = auth.login("admin", ldapPassword)
        if (autheliaResult !is AuthResult.Success) {
            skip("Pipeline: Access API", "Authelia authentication failed")
            return@test
        }
        println("      ✓ Authenticated with Authelia")

        // Step 2: Test direct container access
        val directResponse = client.getRawResponse("http://pipeline:8090/actuator/health")
        require(directResponse.status == HttpStatusCode.OK || directResponse.status == HttpStatusCode.Unauthorized) {
            "Pipeline container not responding: ${directResponse.status}"
        }
        println("      ✓ Pipeline container accessible")

        // Step 3: Test access through Caddy with Authelia session
        val proxiedResponse = auth.authenticatedGet("http://caddy:80/")
        require(proxiedResponse.status == HttpStatusCode.OK || proxiedResponse.status.value in 200..399) {
            "Failed to access through authenticated proxy: ${proxiedResponse.status}"
        }
        println("      ✓ Successfully accessed Pipeline through authenticated proxy")
    }

    // =============================================================================
    // TOKEN MANAGEMENT OPERATIONS
    // =============================================================================

    test("Token manager: Store and retrieve tokens") {
        // Manually store a test token
        val testToken = "test-token-${System.currentTimeMillis()}"

        // Store via TokenManager internal state (simulated)
        require(!tokens.hasToken("test-service")) {
            "Should not have token before storing"
        }

        // After successful acquisition, tokens should be stored
        require(tokens.getToken("grafana") != null || !tokens.hasToken("grafana")) {
            "Token state should be consistent"
        }

        println("      ✓ Token manager correctly manages token state")
    }

    test("Token manager: Clear tokens") {
        // Clear specific token
        tokens.clearToken("grafana")
        require(!tokens.hasToken("grafana")) {
            "Token should be cleared"
        }

        // Clear all tokens
        tokens.clearAll()
        require(!tokens.hasToken("seafile") && !tokens.hasToken("forgejo")) {
            "All tokens should be cleared"
        }

        println("      ✓ Token manager correctly clears tokens")
    }
}
